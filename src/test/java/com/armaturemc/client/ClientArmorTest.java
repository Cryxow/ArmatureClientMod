package com.armaturemc.client;

import com.google.gson.*;
import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientArmorTest {
    @Test void nativeItemCoordinatesBecomeBodyLocalArmorAndKeepAtlasUvs() {
        var layer = ClientArmor.parse(fixture()).getFirst();
        assertEquals(EquipmentSlot.CHEST, layer.slot());
        var vertices = layer.quads().getFirst().vertices();
        assertEquals(-2.8125 / 16, vertices.getFirst().x(), 1e-6);
        assertEquals(.9375 / 16, vertices.getFirst().y(), 1e-6);
        assertEquals(2.8125 / 16, vertices.getFirst().z(), 1e-6);
        assertEquals(48.0 / 64, vertices.getFirst().u(), 1e-6);
        assertEquals(20.0 / 32, vertices.getFirst().v(), 1e-6);
        assertEquals(1, layer.quads().getFirst().normal().length(), 1e-6);
        assertEquals(1, layer.model().allParts().size());
    }

    @Test void nonArmorAndDuplicateSlotsCannotEnterEquipmentRenderer() {
        JsonArray layers = fixture(); layers.get(0).getAsJsonObject().addProperty("slot", "MAINHAND");
        assertThrows(IllegalArgumentException.class, () -> ClientArmor.parse(layers));
        JsonArray duplicate = fixture(); duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ClientArmor.parse(duplicate));
    }

    @Test void legacyDoubleSidedMinecraftTemplateKeepsBothCubesAndObjectRotation() {
        JsonArray layers = fixture();
        JsonObject cube = layers.get(0).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        cube.add("rotation", JsonParser.parseString("{\"axis\":\"y\",\"angle\":0,\"origin\":[8,8,8]}"));
        layers.get(0).getAsJsonObject().getAsJsonArray("cubes").add(cube.deepCopy());
        assertEquals(2, ClientArmor.parse(layers).getFirst().quads().size());
    }

    private static JsonArray fixture() {
        return JsonParser.parseString("""
            [{"slot":"CHEST","cubes":[{"from":[5.1875,-4.1875,5.1875],"to":[10.8125,8.9375,10.8125],
              "faces":{"north":{"texture":"#0","uv":[11,10,12,16]}}}]}]
            """).getAsJsonArray();
    }
}
