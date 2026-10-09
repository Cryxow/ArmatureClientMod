package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.armaturemc.client.protocol.ModelTransfer;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArmatureClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArmatureClient");
    private static final Hand[] HANDS = java.util.stream.IntStream.range(0, ClientProtocol.MODEL_CHANNELS)
        .mapToObj(Hand::new).toArray(Hand[]::new);
    private static final ClientModelSessions<ClientModel> SESSIONS = new ClientModelSessions<>(8, 64L * 1024 * 1024,
        ClientModel::freshPresentation, ClientModel::cacheBytes, ClientModel::close);
    private static final ExecutorService MODELS = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "Armature model compiler"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static long lastHello;
    private static long connectionGeneration;
    private static boolean accepting;
    private static final ClientRendererHandoff handoff = new ClientRendererHandoff(true);
    private static int resourceReloads;
    private static long resourceReloadFinished;
    private static ClientRenderingControl rendering;
    private static long frameAt, lastFrameAt;
    private static ClientCameraEffect frameCamera = ClientCameraEffect.IDENTITY;

    public static boolean isRenderingEnabled() { return rendering == null || rendering.enabled(); }
    public static boolean isRendererSwitchPending() { return handoff.pending(); }
    public static boolean isRendererActive() { return handoff.active(); }
    public static void resourceReload(CompletableFuture<Void> future) {
        resourceReloads++;
        future.whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
            resourceReloads = Math.max(0, resourceReloads - 1); resourceReloadFinished = System.nanoTime();
        }));
    }
    public static void setRenderingEnabled(boolean enabled) throws IOException { rendering.setEnabled(enabled); }

    @Override public void onInitializeClient() {
        rendering = new ClientRenderingControl(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
            .resolve("armature-client.json"), handoff::preferenceChanged, version -> {
                if (Minecraft.getInstance().player != null) send(new ClientProtocol.Hello(version));
                lastHello = System.nanoTime();
            });
        try { rendering.load(); }
        catch (IOException failure) { LOGGER.warn("Unable to load Armature client configuration; using defaults", failure); }
        handoff.reset(isRenderingEnabled());
        PayloadTypeRegistry.playS2C().register(ArmaturePayload.TYPE, ArmaturePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ArmaturePayload.TYPE, ArmaturePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ArmaturePayload.TYPE, (payload, context) -> {
            try {
                ClientProtocol.Message message = ClientProtocol.decode(payload.bytes());
                context.client().execute(() -> receive(message));
            } catch (IOException | IllegalArgumentException malformed) {
                context.client().execute(() -> fail("Invalid Armature payload", malformed));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> { reset(); handoff.reset(isRenderingEnabled()); }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { reset(); handoff.reset(isRenderingEnabled()); }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.nanoTime();
            var pack = handoff.pack();
            boolean present = !pack.equals(ClientProtocol.NO_PACK) && client.getResourceManager().listPacks()
                .anyMatch(resources -> resources.packId().endsWith("/" + pack));
            var ready = handoff.ready(resourceReloads > 0 || client.getOverlay() != null, present);
            if (ready != null) send(ready);
            if (handoff.active() && Arrays.stream(HANDS).anyMatch(hand -> hand.bound() && !hand.owned())) {
                fail("Armature frame stream expired", new IllegalStateException("No fresh frame for one second"));
                return;
            }
            if (client.player != null && ClientPlayNetworking.canSend(ArmaturePayload.TYPE)
                && now - lastHello > 5_000_000_000L) {
                send(new ClientProtocol.Hello(handoff.helloVersion(rendering.helloVersion()))); lastHello = now;
            }
        });
        LOGGER.info("Armature client preview initialized for Minecraft 1.21.8");
    }

    private static void receive(ClientProtocol.Message message) {
        if (message instanceof ClientProtocol.RendererSwitch change) { handoff.prepare(change); return; }
        if (message instanceof ClientProtocol.RendererCommit commit) {
            boolean previous = handoff.active();
            if (handoff.commit(commit)) {
                if (!handoff.active() || !previous) reset();
                accepting = handoff.active();
                if (handoff.active() != isRenderingEnabled()) LOGGER.warn("Renderer switch failed; retaining current renderer until the fallback pack is ready");
            }
            return;
        }
        // Ignore frames/chunks already in flight when the user relinquishes client ownership.
        if (!handoff.active() || !accepting) return;
        try {
            if (message instanceof ClientProtocol.ModelChunk chunk) {
                Hand hand = HANDS[chunk.slot()];
                // A cache hit already owns this exact bundle; chunks sent before Ready are harmless.
                if (hand.model() != null && chunk.hash().equals(hand.hash)) return;
                if (chunk.index() == 0) {
                    // Options and the ownership frame now precede chunks. Do not discard them.
                    if (hand.hash != null && !chunk.hash().equals(hand.hash)) hand.clear();
                    hand.epoch++; hand.hash = chunk.hash(); hand.transfer = new ModelTransfer(chunk);
                }
                if (hand.transfer == null) throw new IllegalArgumentException("Chunk without transfer");
                Optional<byte[]> bytes = hand.transfer.accept(chunk);
                if (bytes.isEmpty()) return;
                long epoch = hand.epoch, generation = connectionGeneration;
                String hash = chunk.hash(); hand.transfer = null;
                MODELS.execute(() -> {
                    final ClientModel model;
                    try { model = ClientModel.parse(bytes.get()); }
                    catch (RuntimeException malformed) {
                        Minecraft.getInstance().execute(() -> {
                            if (generation == connectionGeneration && epoch == hand.epoch) fail("Client model rejected", malformed);
                        }); return;
                    }
                    Minecraft.getInstance().execute(() -> {
                        if (generation != connectionGeneration || epoch != hand.epoch) return;
                        try {
                            if (hand.current == null || !SESSIONS.accepts(hand.slot, hand.current.session(), hash)) return;
                            model.upload(hash, chunk.slot());
                            if (!SESSIONS.attach(hand.slot, hand.current.session(), hash, model)) { model.close(); return; }
                            hand.hash = hash;
                            if (model.animation != null && hand.options != null) model.animation.configure(hand.options);
                            hand.optionsPending = false;
                            if (hand.current != null && hash.equals(hand.current.hash()))
                                model.receive(hand.current, hand.receivedAt, viewer(1.0 / 60));
                            send(new ClientProtocol.Ready(chunk.slot(), hash));
                        } catch (IOException | RuntimeException failure) { fail("Texture upload failed", failure); }
                    });
                });
            } else if (message instanceof ClientProtocol.Frame frame) {
                Hand hand = HANDS[frame.slot()];
                if (hand.current != null && frame.sequence() <= hand.current.sequence()) return;
                if ((hand.hash != null && !frame.hash().equals(hand.hash))
                    || (hand.current != null && !frame.session().equals(hand.current.session()))) {
                    ClientHeldItems incomingItems = hand.items;
                    var incomingOptions = hand.options;
                    hand.clear();
                    hand.options = incomingOptions; hand.optionsPending = true;
                    if (incomingItems != null && incomingItems.session().equals(frame.session())) hand.items = incomingItems;
                }
                hand.hash = frame.hash();
                ClientModel previousModel = hand.model();
                ClientModel model = SESSIONS.offer(frame.slot(), frame.session(), frame.hash());
                if (model != null) {
                    if ((model != previousModel || hand.optionsPending) && model.animation != null && hand.options != null)
                        model.animation.configure(hand.options);
                    hand.optionsPending = false;
                    if (model != previousModel) send(new ClientProtocol.Ready(frame.slot(), frame.hash()));
                }
                hand.current = frame; hand.receivedAt = System.nanoTime();
                if (model != null) model.receive(frame, hand.receivedAt, viewer(1.0 / 60));
                hand.entities.clear(); for (int id : frame.entityIds()) hand.entities.add(id);
            } else if (message instanceof ClientProtocol.HeldItems items) {
                HANDS[items.slot()].items = ClientHeldItems.decode(items, Minecraft.getInstance().level.registryAccess());
            } else if (message instanceof ClientProtocol.Options options) {
                Hand hand = HANDS[options.slot()];
                hand.options = com.google.gson.JsonParser.parseString(options.json()).getAsJsonObject();
                // Options precede the ownership frame and may belong to a different session.
                hand.optionsPending = true;
            } else if (message instanceof ClientProtocol.Clear clear) HANDS[clear.slot()].clear();
        } catch (RuntimeException failure) { fail("Armature client session reset", failure); }
    }

    public static boolean hidesVanillaHand(InteractionHand hand) {
        if (!canRender()) return false;
        int slot = hand == InteractionHand.MAIN_HAND ? 0 : 1;
        for (int channel = slot; channel < ClientProtocol.MODEL_CHANNELS; channel += 2) {
            if (HANDS[channel].owned() && HANDS[channel].current.replaceHand()) return true;
        }
        return false;
    }
    public static boolean hidesEntity(int id) {
        if (!handoff.active()) return false;
        // Ownership also masks server displays in F1, while scoping and in third person.
        // Render-pass eligibility must never let an owned server model flash back in.
        if (Minecraft.getInstance().player == null) return false;
        for (Hand hand : HANDS) {
            if (!hand.owned()) continue;
            if (hand.entities.contains(id)) return true;
        }
        return false;
    }

    /** One sample shared by all projection calls and the hand pass in this rendered frame. */
    public static void beginFrame(float partialTick) {
        frameAt = System.nanoTime(); frameCamera = ClientCameraEffect.IDENTITY;
        double delta = lastFrameAt == 0 ? 1.0 / 60 : Math.clamp((frameAt - lastFrameAt) / 1e9, .0001, .25);
        lastFrameAt = frameAt;
        if (!canRender()) return;
        var context = viewer(delta, partialTick);
        var player = Minecraft.getInstance().player;
        var input = new com.armaturemc.renderer.internal.runtime.NativeMotionSampler.Input(
            net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX()),
            net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ()),
            player.getViewYRot(partialTick), player.getViewXRot(partialTick));
        try {
            for (Hand hand : HANDS) {
                if (!hand.active()) continue;
                var model = hand.model();
                if (model.animation == null) throw new IllegalArgumentException("Model lacks client hierarchy");
                model.animation.capture(input, frameAt);
                model.frameMatrices = model.animation.matrices(frameAt, context, hand.current.bones());
            }
            for (int slot = 0; slot < ClientProtocol.MODEL_CHANNELS; slot += 2) {
                Hand hand = HANDS[slot];
                if (hand.active() && hand.current.replaceHand()) {
                    frameCamera = hand.model().animation.cameraEffect(frameAt, context); break;
                }
            }
        } catch (RuntimeException failure) { fail("Frame sampling failed", failure); }
    }

    public static ClientCameraEffect cameraEffect() {
        return canRender() ? frameCamera : ClientCameraEffect.IDENTITY;
    }

    /** Invoked in Minecraft's actual hand pass, after the world depth buffer is cleared. */
    public static void render(PoseStack stack, MultiBufferSource buffers, int light, float partialTick) {
        if (!canRender()) return;
        // GameRenderer supplies inverse camera * vanilla bob/hurt to its hand
        // renderer. Armature has its own motion; cancel only the active camera
        // view in a separate stack, so both legacy and modern rigs stay attached
        // to the viewport and vanilla hands keep their original stack.
        stack = new PoseStack();
        stack.mulPose(ClientViewTransform.viewport(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()));
        for (Hand hand : HANDS) {
            if (!hand.active()) continue;
            stack.pushPose();
            try {
                stack.mulPose(cameraEffect().handTransform());
                stack.mulPose(ClientViewTransform.forFrame(hand.current, partialTick));
                hand.model().render(hand.model().frameMatrices, stack, buffers, light,
                    hand.items != null && hand.items.session().equals(hand.current.session()) ? hand.items : null);
            } catch (RuntimeException failure) { fail("Viewport rendering failed", failure); return; }
            finally { stack.popPose(); }
        }
    }

    private static boolean canRender() {
        Minecraft client = Minecraft.getInstance();
        return handoff.active() && client.player != null && client.level != null && client.options.getCameraType().isFirstPerson()
            && !client.options.hideGui && !client.player.isSpectator() && !client.player.isScoping();
    }
    private static com.armaturemc.renderer.internal.animation.MolangContext viewer(double delta) {
        return viewer(delta, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }
    private static com.armaturemc.renderer.internal.animation.MolangContext viewer(double delta, float partialTick) {
        var client = Minecraft.getInstance(); var player = client.player;
        var motion = player.getDeltaMovement(); double speed = motion.horizontalDistance();
        return new com.armaturemc.renderer.internal.animation.MolangContext(player.getViewYRot(partialTick), player.getViewXRot(partialTick),
            ClientMovement.distance(player, partialTick), speed * 20,
            0, delta, (client.level.getGameTime() + partialTick) / 20.0,
            System.nanoTime() / 1e9, (player.tickCount + partialTick) / 20.0, player.getHealth(), player.hurtTime,
            speed > .001, player.onGround(), player.isCrouching(), player.isSprinting(), player.isSwimming(), Map.of(), null);
    }
    private static void send(ClientProtocol.Message message) {
        if (ClientPlayNetworking.canSend(ArmaturePayload.TYPE)) {
            if (message instanceof ClientProtocol.Hello hello && hello.version() == ClientProtocol.VERSION) accepting = true;
            ClientPlayNetworking.send(new ArmaturePayload(ClientProtocol.encode(message)));
        }
    }
    private static void fail(String reason, Exception failure) {
        LOGGER.warn(reason + "; restoring server rendering", failure);
        send(new ClientProtocol.Hello(0)); reset();
        handoff.fail();
        lastHello = System.nanoTime();
    }
    private static void reset() {
        accepting = false;
        connectionGeneration++; SESSIONS.reset(); for (Hand hand : HANDS) hand.clear(); lastHello = lastFrameAt = frameAt = 0;
        frameCamera = ClientCameraEffect.IDENTITY;
    }
    private static final class Hand {
        final int slot;
        Hand(int slot) { this.slot = slot; }
        ModelTransfer transfer; String hash;
        com.google.gson.JsonObject options;
        boolean optionsPending;
        ClientProtocol.Frame current;
        ClientHeldItems items;
        long receivedAt, epoch;
        final Set<Integer> entities = new HashSet<>();
        ClientModel model() { return SESSIONS.get(slot); }
        boolean active() {
            return model() != null && owned();
        }
        boolean owned() {
            return bound()
                && ClientRendererHandoff.frameFresh(System.nanoTime(), receivedAt, resourceReloads > 0, resourceReloadFinished);
        }
        boolean bound() { return current != null && SESSIONS.matches(slot, current.session(), current.hash()); }
        void clear() {
            epoch++;
            SESSIONS.clear(slot);
            transfer = null; hash = null; current = null; receivedAt = 0;
            items = null;
            entities.clear(); options = null; optionsPending = false;
        }
    }
}
