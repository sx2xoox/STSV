package dev.stsv.core.furniture;

import dev.stsv.core.content.FurnitureSpec;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnitureRotationTest {

    private static final FurnitureSpec CARDINAL = new FurnitureSpec(List.of(), true, FurnitureSpec.Rotation.CARDINAL,
            null, new Vector3f(), new Vector3f(1), -1, List.of(), List.of());

    @Test
    void frontFacesThePlayer() {
        // player looking north (yaw 180) -> furniture faces south (yaw 0), towards the player
        assertEquals(0f, FurnitureModule.placementYaw(CARDINAL, 180f));
        assertEquals(90f, FurnitureModule.placementYaw(CARDINAL, -90f)); // looking east -> face west
        assertEquals(180f, FurnitureModule.placementYaw(CARDINAL, 10f)); // looking ~south -> face north
        assertEquals(270f, FurnitureModule.placementYaw(CARDINAL, 95f)); // looking ~west -> face east
    }

    @Test
    void cellsFollowTheModel() {
        // Model +x (its east side) appears on the viewer's left when they face the model's front.
        // Player looks north: their left is west (-x).
        assertArrayEquals(new int[]{-1, 0, 0}, FurnitureModule.rotate(new int[]{1, 0, 0}, 0f));
        // Player looks east (furniture yaw 90): their left is north (-z).
        assertArrayEquals(new int[]{0, 0, -1}, FurnitureModule.rotate(new int[]{1, 0, 0}, 90f));
        // Player looks south (yaw 180): left is east (+x).
        assertArrayEquals(new int[]{1, 0, 0}, FurnitureModule.rotate(new int[]{1, 0, 0}, 180f));
        // Height is never rotated.
        assertArrayEquals(new int[]{0, 1, 0}, FurnitureModule.rotate(new int[]{0, 1, 0}, 270f));
    }
}
