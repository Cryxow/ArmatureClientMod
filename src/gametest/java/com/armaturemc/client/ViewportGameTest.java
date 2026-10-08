package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import org.joml.Matrix4f;

/** Exercises actual Fabric play networking and the hand-render mixin in an integrated test world. */
public final class ViewportGameTest implements FabricClientGameTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aCioAAAAASUVORK5CYII=";

    @Override public void runTest(ClientGameTestContext context) {
        String external = System.getProperty("armature.client.testServer");
        if (external != null) { testPluginConnection(context, external); return; }
        byte[] bytes = model(); String hash = ClientProtocol.hash(bytes); UUID session = UUID.randomUUID();
        AtomicBoolean ready = new AtomicBoolean(), running = new AtomicBoolean(true), stream = new AtomicBoolean(true);
        AtomicLong sequence = new AtomicLong();
        long clipEpoch = System.nanoTime();
        ServerPlayNetworking.registerGlobalReceiver(ArmaturePayload.TYPE, (payload, network) -> {
            try {
                var message = ClientProtocol.decode(payload.bytes());
                if (message instanceof ClientProtocol.Hello hello && hello.version() == ClientProtocol.VERSION && !ready.get()) {
                    ServerPlayNetworking.send(network.player(), new ArmaturePayload(ClientProtocol.encode(
                        new ClientProtocol.ModelChunk(0, hash, bytes.length, 0, bytes))));
                } else if (message instanceof ClientProtocol.Ready ack && ack.slot() == 0 && ack.hash().equals(hash)) {
                    ready.set(true);
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!running.get() || !ready.get() || !stream.get()) return;
            double elapsed = (System.nanoTime() - clipEpoch) / 1e9;
            float[] body = {.45f, -.35f, -.8f, 0, 0, 0, 1};
            float[] arm = {.65f, .3f, -1.1f, 0, 0, 0, 1};
            var frame = new ClientProtocol.Frame(0, session, hash, sequence.incrementAndGet(), true,
                new int[]{123456}, List.of(new ClientProtocol.Bone("body", true, body),
                    new ClientProtocol.Bone("arm", true, arm)), ClientProtocol.View.defaults(),
                "{\"loop\":{\"name\":\"move\",\"epoch\":1,\"elapsed\":" + elapsed + "}}");
            for (var player : server.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, new ArmaturePayload(ClientProtocol.encode(frame)));
            }
        });
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            context.runOnClient(client -> { client.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD)); });
            context.waitFor(client -> ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND), 400);
            context.runOnClient(client -> {
                if (!ready.get()) throw new AssertionError("Client became active without model readiness ACK");
                if (!ArmatureClient.hidesEntity(123456)) throw new AssertionError("Server model id was not masked");
                if (ArmatureClient.hidesEntity(123457)) throw new AssertionError("Unrelated entity was masked");
                if (ArmatureClient.hidesVanillaHand(InteractionHand.OFF_HAND)) throw new AssertionError("Unreplaced offhand was masked");
            });
            context.takeScreenshot("armature-client-active");
            stream.set(false);
            AtomicReference<Float> before = new AtomicReference<>();
            context.runOnClient(client -> before.set(clientPoseX()));
            context.waitTicks(4);
            context.runOnClient(client -> {
                if (Math.abs(clientPoseX() - before.get()) < .001)
                    throw new AssertionError("Authored animation stopped between server state messages");
            });
            stream.set(true);
            context.runOnClient(client -> client.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                new ItemStack(Items.DIAMOND_CHESTPLATE)));
            context.waitTicks(20);
            context.takeScreenshot("armature-client-diamond-probe");
            context.runOnClient(client -> {
                ItemStack armor = new ItemStack(Items.LEATHER_CHESTPLATE);
                armor.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                    new net.minecraft.world.item.component.DyedItemColor(0xff0000));
                armor.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                client.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, armor);
            });
            context.waitTicks(20);
            context.takeScreenshot("armature-client-leather-probe");
            stream.set(false);
            context.waitFor(client -> !ArmatureClient.hidesEntity(123456), 100);
            context.takeScreenshot("armature-client-fallback");
        } finally {
            running.set(false); ServerPlayNetworking.unregisterGlobalReceiver(ArmaturePayload.TYPE.id());
        }
    }

    private void testPluginConnection(ClientGameTestContext context, String address) {
        String pack = System.getProperty("armature.client.testPack");
        if (pack != null) {
            AtomicReference<java.util.concurrent.CompletableFuture<Void>> reload = new AtomicReference<>();
            context.runOnClient(client -> {
                try {
                    var directory = client.gameDirectory.toPath().resolve("resourcepacks");
                    java.nio.file.Files.createDirectories(directory);
                    java.nio.file.Files.copy(java.nio.file.Path.of(pack), directory.resolve("armature-parity.zip"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                var repository = client.getResourcePackRepository(); repository.reload();
                var selected = new ArrayList<>(repository.getSelectedIds()); selected.add("file/armature-parity.zip");
                repository.setSelected(selected); reload.set(client.reloadResourcePacks());
            });
            context.waitFor(client -> reload.get().isDone(), 2000);
            context.runOnClient(client -> reload.get().join());
        }
        context.runOnClient(client -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
            client.screen, client, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
            new net.minecraft.client.multiplayer.ServerData("Armature test", address,
                net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.player != null && client.level != null, 5000);
        context.runOnClient(client -> {
            if (client.player.isDeadOrDying()) client.getConnection().send(
                new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                    net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        });
        context.waitFor(client -> client.player != null && client.player.isAlive(), 200);
        context.waitForScreen(null);
        context.waitFor(client -> ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND), 5000);
        context.waitTicks(40);
        context.takeScreenshot("armature-folia-client-active");
        if (Boolean.getBoolean("armature.client.testFraming")) {
            context.runOnClient(client -> { client.player.setXRot(0); client.player.setYRot(0); });
            context.waitTicks(40);
            context.takeScreenshot("armature-framing-client");
            context.runOnClient(client -> {
                try {
                    var hands = ArmatureClient.class.getDeclaredField("HANDS"); hands.setAccessible(true);
                    var hand = ((Object[])hands.get(null))[0];
                    var current = hand.getClass().getDeclaredField("current"); current.setAccessible(true);
                    var frame = (ClientProtocol.Frame)current.get(hand);
                    if (!frame.view().mountedOrigin() || frame.view().fov() != 110)
                        throw new AssertionError("Expected server's mounted origin and 110-degree viewmodel projection");
                    System.out.println("Armature framing projection=" + frame.view());
                    // Test-only release: same player, model, camera and pack, with the server path visible.
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ArmaturePayload(
                        ClientProtocol.encode(new ClientProtocol.Hello(0))));
                    var clear = ArmatureClient.class.getDeclaredMethod("reset"); clear.setAccessible(true); clear.invoke(null);
                    var hello = ArmatureClient.class.getDeclaredField("lastHello"); hello.setAccessible(true);
                    hello.setLong(null, System.nanoTime() + 120_000_000_000L);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            context.waitTicks(40);
            context.takeScreenshot("armature-framing-server");
            context.runOnClient(client -> {
                try {
                    var hello = ArmatureClient.class.getDeclaredField("lastHello"); hello.setAccessible(true); hello.setLong(null, 0);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            context.waitFor(client -> ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND), 5000);
        }
        if (Boolean.getBoolean("armature.client.testArmor")) {
            context.runOnClient(client -> client.getConnection().sendCommand(
                "gamemode creative"));
            context.waitFor(client -> client.gameMode.getPlayerMode().isCreative(), 200);
            // Folia does not register /item. Use the actual creative inventory protocol instead.
            context.runOnClient(client -> {
                ItemStack armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
                client.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, armor);
                client.gameMode.handleCreativeModeItemAdd(armor, 6);
            });
            context.waitTicks(40);
            context.runOnClient(client -> {
                if (!ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND))
                    throw new AssertionError("Equipped diamond armor released client takeover");
                assertArmorPrepared(client);
            });
            context.takeScreenshot("armature-folia-client-diamond-armor");
            context.runOnClient(client -> {
                ItemStack armor = new ItemStack(Items.LEATHER_CHESTPLATE);
                armor.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                    new net.minecraft.world.item.component.DyedItemColor(0xff0000));
                armor.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                client.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, armor);
                client.gameMode.handleCreativeModeItemAdd(armor, 6);
            });
            context.waitTicks(40);
            context.takeScreenshot("armature-folia-client-dyed-leather-armor");
        }
        context.runOnClient(client -> client.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false));
        context.waitFor(client -> client.player == null, 200);
        context.waitTicks(20);
        context.runOnClient(client -> {
            if (ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND)) throw new AssertionError("Mask survived disconnect");
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),
                client, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                new net.minecraft.client.multiplayer.ServerData("Armature reconnect", address,
                    net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
        });
        context.waitFor(client -> ArmatureClient.hidesVanillaHand(InteractionHand.MAIN_HAND), 5000);
        context.waitForScreen(null);
        context.waitTicks(40);
        context.takeScreenshot("armature-folia-client-reconnected");
        context.runOnClient(client -> client.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false));
    }

    private static void assertArmorPrepared(net.minecraft.client.Minecraft client) {
        try {
        var handsField = ArmatureClient.class.getDeclaredField("HANDS"); handsField.setAccessible(true);
        Object hand = ((Object[])handsField.get(null))[0];
        var modelField = hand.getClass().getDeclaredField("model"); modelField.setAccessible(true);
        ClientModel model = (ClientModel) modelField.get(hand);
        long layers = model.parts.stream().flatMap(part -> part.armor().stream()).count();
        if (layers == 0) throw new AssertionError("Plugin supplied no armor meshes");
        var rendererField = ClientArmor.class.getDeclaredField("renderer"); rendererField.setAccessible(true);
        Object renderer = rendererField.get(null);
        if (renderer == null) throw new AssertionError("Minecraft equipment renderer did not initialize");
        var assetsField = renderer.getClass().getDeclaredField("equipmentAssets"); assetsField.setAccessible(true);
        var assets = (net.minecraft.client.resources.model.EquipmentAssetManager) assetsField.get(renderer);
        var asset = client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
            .get(net.minecraft.core.component.DataComponents.EQUIPPABLE).assetId().orElseThrow();
        int textures = assets.get(asset).getLayers(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID).size();
        if (textures == 0) throw new AssertionError("Minecraft resolved no armor texture layers");
        System.out.println("Armature armor meshes=" + layers + " equipment layers=" + textures + " asset=" + asset);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static float clientPoseX() {
        try {
            var hands = ArmatureClient.class.getDeclaredField("HANDS"); hands.setAccessible(true);
            Object hand = ((Object[])hands.get(null))[0];
            var method = hand.getClass().getDeclaredMethod("matrices", float.class); method.setAccessible(true);
            @SuppressWarnings("unchecked") var poses = (Map<String, Matrix4f>)method.invoke(hand, 1f);
            return poses.get("body").m30();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static byte[] model() {
        JsonObject root = new JsonObject(), textureMap = new JsonObject(), texture = new JsonObject();
        texture.addProperty("png", PNG); texture.addProperty("width", 1); texture.addProperty("height", 1);
        textureMap.add("0", texture); root.add("textures", textureMap);
        JsonArray bones = new JsonArray();
        JsonObject bone = new JsonObject(); bone.addProperty("id", "body"); bone.addProperty("role", "none");
        bone.add("pivot", JsonParser.parseString("[0,0,0]"));
        JsonObject cube = JsonParser.parseString("{\"from\":[-2,-2,-2],\"to\":[2,2,2],\"faces\":{}}").getAsJsonObject();
        for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
            JsonObject face = JsonParser.parseString("{\"texture\":0,\"uv\":[0,0,1,1]}").getAsJsonObject();
            cube.getAsJsonObject("faces").add(direction, face);
        }
        JsonArray cubes = new JsonArray(); cubes.add(cube); bone.add("cubes", cubes); bones.add(bone); root.add("bones", bones);
        // Controlled arm placement makes equipment materials visible independently of authored camera framing.
        bones.add(JsonParser.parseString("""
            {"id":"arm","role":"right_full_arm","pivot":[0,0,0],"cubes":[],
             "armor":[{"slot":"CHEST","cubes":[
              {"from":[5.1875,-4.1875,5.1875],"to":[10.8125,8.9375,10.8125],"faces":{
               "north":{"texture":"#0","uv":[11,10,12,16]},
               "south":{"texture":"#0","uv":[13,10,14,16]},
               "east":{"texture":"#0","uv":[10,10,11,16]},
               "west":{"texture":"#0","uv":[12,10,13,16]},
               "up":{"texture":"#0","uv":[12,10,11,8]},
               "down":{"texture":"#0","uv":[13,8,12,10]}}}]}]}
            """));
        root.add("animation", JsonParser.parseString("""
          {"name":"test","groups":[{"uuid":"body","name":"body","origin":[0,0,0]},
           {"uuid":"arm","name":"arm","origin":[0,0,0]}],"elements":[],
           "outliner":[{"uuid":"body","children":[]},{"uuid":"arm","children":[]}],
           "animations":[{"name":"move","length":2,"loop":"loop","animators":{
            "body":{"name":"body","type":"bone","keyframes":[
             {"channel":"position","time":0,"interpolation":"linear","data_points":[{"x":0,"y":0,"z":0}]},
             {"channel":"position","time":2,"interpolation":"linear","data_points":[{"x":3.2,"y":0,"z":0}]}]}}}]}
          """));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
