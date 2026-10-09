package com.armaturemc.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check the built mixin configuration against the exact target game's bytecode.
 * No Minecraft window, renderer, or Mixin transformation is launched. */
class MinecraftHooksTest {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/";

    @Test void armorReloadUsesTheSameAtlasLookupAsVanillaEquipment() throws Exception {
        var client = read("com/armaturemc/client/ClientArmor").methods.stream()
            .filter(method -> method.name.equals("reload")).findFirst().orElseThrow();
        var vanilla = read("net/minecraft/client/renderer/entity/EntityRendererProvider$Context").methods.stream()
            .filter(method -> method.name.equals("<init>")).findFirst().orElseThrow();
        assertEquals(armorAtlasLookup(vanilla), armorAtlasLookup(client),
            "Armor reload must use the atlas definition ID for AtlasManager, not its texture path");
    }

    private static List<String> armorAtlasLookup(MethodNode method) {
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.startsWith("getAtlas")) continue;
            var previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.name.startsWith("ARMOR_TRIMS"))
                return List.of(call.owner, call.name, call.desc, field.owner, field.name, field.desc);
        }
        return fail("Missing vanilla armor atlas lookup in " + method.name);
    }

    @Test void everyConfiguredHookMatchesTheTargetMinecraftVersion() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("armature-client.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : config.getAsJsonArray("client")) {
                var mixin = read(config.get("package").getAsString().replace('.', '/') + "/" + entry.getAsString());
                var definition = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN + "Mixin;");
                assertNotNull(definition, mixin.name);
                @SuppressWarnings("unchecked") var targets = (List<Type>) value(definition, "value");
                assertNotNull(targets, mixin.name);
                for (var type : targets) verify(mixin, read(type.getInternalName()));
            }
        }
    }

    private void verify(ClassNode mixin, ClassNode target) {
        // Mixin classifies an interface with any ordinary default method as an
        // interface mixin, which cannot target a concrete Minecraft class.
        if ((mixin.access & Opcodes.ACC_INTERFACE) != 0 && (target.access & Opcodes.ACC_INTERFACE) == 0)
            for (var method : mixin.methods) if (!method.name.equals("<clinit>") && (method.access & Opcodes.ACC_SYNTHETIC) == 0)
                assertTrue(annotation(method.visibleAnnotations, method.invisibleAnnotations, MIXIN + "gen/Accessor;") != null
                    || annotation(method.visibleAnnotations, method.invisibleAnnotations, MIXIN + "gen/Invoker;") != null,
                    mixin.name + " interface mixin cannot target a class: " + method.name);
        for (var field : mixin.fields) if (annotation(field.visibleAnnotations, field.invisibleAnnotations, MIXIN + "Shadow;") != null)
            assertTrue(target.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)),
                () -> mixin.name + " missing shadow " + field.name + field.desc);
        for (var method : mixin.methods) {
            var accessor = annotation(method.visibleAnnotations, method.invisibleAnnotations, MIXIN + "gen/Accessor;");
            if (accessor != null) {
                var name = value(accessor, "value");
                assertTrue(target.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(Type.getReturnType(method.desc).getDescriptor())),
                    () -> mixin.name + " missing accessor " + name);
            }
            var invoker = annotation(method.visibleAnnotations, method.invisibleAnnotations, MIXIN + "gen/Invoker;");
            if (invoker != null) {
                var name = value(invoker, "value");
                assertTrue(target.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(method.desc)),
                    () -> mixin.name + " missing invoker " + name + method.desc);
            }
            var inject = annotation(method.visibleAnnotations, method.invisibleAnnotations, MIXIN + "injection/Inject;");
            if (inject == null) continue;
            @SuppressWarnings("unchecked") var selectors = (List<String>) value(inject, "method");
            for (String selector : selectors) {
                int split = selector.indexOf('(');
                String name = split < 0 ? selector : selector.substring(0, split);
                var matches = target.methods.stream().filter(m -> m.name.equals(name)
                    && (split < 0 || m.desc.equals(selector.substring(split)))).toList();
                assertFalse(matches.isEmpty(), mixin.name + " missing injection " + selector);
                for (var match : matches) {
                    var handler = Type.getArgumentTypes(method.desc);
                    var parameters = Type.getArgumentTypes(match.desc);
                    assertTrue(handler.length == 1 || handler.length == parameters.length + 1,
                        mixin.name + " mismatched injection parameters " + selector);
                    if (handler.length > 1) assertArrayEquals(parameters, Arrays.copyOf(handler, handler.length - 1),
                        mixin.name + " mismatched injection types " + selector);
                    String callback = Type.getReturnType(match.desc).equals(Type.VOID_TYPE) ? "CallbackInfo" : "CallbackInfoReturnable";
                    assertEquals("org/spongepowered/asm/mixin/injection/callback/" + callback, handler[handler.length - 1].getInternalName());
                }
            }
        }
    }

    private ClassNode read(String name) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        for (var list : Arrays.asList(visible, invisible)) if (list != null)
            for (var entry : list) if (entry.desc.equals(descriptor)) return entry;
        return null;
    }
    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
}
