#!/usr/bin/env python3
"""Checks the server classes the core plugin reaches by reflection against a server jar, without running it.

    python3 -I tools/nms_check.py <server jar>        (the jar inside Paper's bundle: META-INF/versions/<v>/server-<v>.jar)

The plugin looks these names up at run time (CosmeticPackets), so a name that moved in a new Minecraft version is
only seen on a real server: a warning in the log and the feature off. This reads the jar with javap and lists every
name that is not there. Run it against the new server jar before a version change, and whenever a name is added to
CosmeticPackets (add it to WANTED below too).
"""
import subprocess
import sys

GAME = "net.minecraft.network.protocol.game."
ITEM = "net.minecraft.world.item.ItemStack"

# class -> text each of which must appear in "javap -p" of the class
WANTED = {
    "net.minecraft.world.entity.EntityTypes": [" INTERACTION;"],
    "net.minecraft.world.phys.Vec3": ["public static final net.minecraft.world.phys.Vec3 ZERO;"],
    "net.minecraft.world.entity.EquipmentSlot": [" HEAD;", " CHEST;", " LEGS;", " FEET;"],
    "net.minecraft.world.entity.Display": [
        " DATA_TRANSLATION_ID;", " DATA_WIDTH_ID;", " DATA_HEIGHT_ID;", " DATA_BILLBOARD_RENDER_CONSTRAINTS_ID;",
        " DATA_BRIGHTNESS_OVERRIDE_ID;"],
    "net.minecraft.world.entity.Display$ItemDisplay": [" DATA_ITEM_STACK_ID;"],
    "net.minecraft.world.entity.Interaction": [" DATA_WIDTH_ID;", " DATA_HEIGHT_ID;"],
    "net.minecraft.network.syncher.EntityDataAccessor": ["public int id();"],
    "net.minecraft.network.syncher.SynchedEntityData$DataValue": [
        "DataValue(int, net.minecraft.network.syncher.EntityDataSerializer<T>, T);",
        " create(net.minecraft.network.syncher.EntityDataAccessor<T>, T);",
        "public int id();", " serializer();", " value();"],
    "net.minecraft.network.FriendlyByteBuf": [
        "public net.minecraft.network.FriendlyByteBuf(io.netty.buffer.ByteBuf);", " writeVarInt(int);",
        " writeVarIntArray(int[]);"],
    "net.minecraft.network.Connection": [],
    "net.minecraft.network.protocol.Packet": [],
    "net.minecraft.network.protocol.BundlePacket": [" subPackets();"],
    ITEM: [" copy();"],
    GAME + "ClientboundAddEntityPacket": [
        "ClientboundAddEntityPacket(int, java.util.UUID, double, double, double, float, float, "
        "net.minecraft.world.entity.EntityType<?>, int, net.minecraft.world.phys.Vec3, double);",
        "public int getId();"],
    GAME + "ClientboundSetEntityDataPacket": ["ClientboundSetEntityDataPacket(int, java.util.List<", "public int id();", " packedItems();"],
    GAME + "ClientboundSetPassengersPacket": [
        "ClientboundSetPassengersPacket(net.minecraft.network.FriendlyByteBuf);", " getVehicle();", " getPassengers();"],
    GAME + "ClientboundBundlePacket": ["public " + GAME + "ClientboundBundlePacket(java.lang.Iterable<"],
    GAME + "ClientboundSetEquipmentPacket": ["ClientboundSetEquipmentPacket(int, java.util.List<", " getEntity();", " getSlots();"],
    GAME + "ClientboundContainerSetSlotPacket": [
        "ClientboundContainerSetSlotPacket(int, int, int, " + ITEM + ");", " getContainerId();", " getStateId();",
        " getSlot();", " getItem();"],
    GAME + "ClientboundContainerSetContentPacket": [
        "ClientboundContainerSetContentPacket(int, int, java.util.List<", " containerId();", " stateId();", " items();",
        " carriedItem();"],
    GAME + "ClientboundSetPlayerInventoryPacket": ["ClientboundSetPlayerInventoryPacket(int, " + ITEM + ");", " slot();", " contents();"],
    GAME + "ClientboundRespawnPacket": [],
    GAME + "ClientboundLoginPacket": [],
}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    missing = []
    for cls, wanted in WANTED.items():
        run = subprocess.run(["javap", "-p", "-cp", sys.argv[1], cls], capture_output=True, text=True)
        if run.returncode != 0 or "class " not in run.stdout and "interface " not in run.stdout and "enum " not in run.stdout:
            missing.append(cls + "  (class not found)")
            continue
        for text in wanted:
            if text not in run.stdout:
                missing.append(cls + "  " + text.strip())
    if missing:
        print("NOT IN THIS SERVER JAR (%d):" % len(missing))
        for m in missing:
            print("  " + m)
        sys.exit(1)
    print("all %d classes and their members are there" % len(WANTED))


if __name__ == "__main__":
    main()
