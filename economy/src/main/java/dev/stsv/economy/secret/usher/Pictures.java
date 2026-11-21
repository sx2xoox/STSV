package dev.stsv.economy.secret.usher;

import dev.stsv.core.util.Text;
import dev.stsv.economy.guide.GuideLayout;
import dev.stsv.economy.secret.Haunt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The thirteen wrong pictures, one per station of the 길라잡이 체험관, each something that was never there (or is there
 * wrong), only for one player. Every picture has one click box: the thing to point at. Station coordinates as in
 * {@link GuideLayout} (lx to the right walking in, lz inward, ly up from the floor).
 */
final class Pictures {

    /** Called every frame (2 ticks) while the player is in the tutorial world. */
    interface Frame {
        void run(Player p, int frame);
    }

    /** One wrong picture as shown to one player. */
    static final class Picture {
        final String station;
        final Props props;
        Interaction answer;
        Frame frame;

        Picture(String station, Props props) {
            this.station = station;
            this.props = props;
        }
    }

    /** What is wrong at each station (for admins: status, docs, the preview). */
    static final Map<String, String> WHAT = Map.ofEntries(
            Map.entry("missions", "주인 없는 이름표 (내 이름이 허공에 떠 있음)"),
            Map.entry("stocks", "주식판에 상장된 내 이름 (▼ 99.97%)"),
            Map.entry("trade", "나에게서 나에게 온 소포"),
            Map.entry("accessories", "한 박자 늦게 따라 하는 거울 속의 나"),
            Map.entry("land", "땅 주인 표지판에 내 이름"),
            Map.entry("bus", "내 발자국 (내가 갈 곳에 먼저 찍힘)"),
            Map.entry("dock", "거꾸로 선 사람 (하역장 직원)"),
            Map.entry("shop", "천막 아래 천장에 붙은 의자"),
            Map.entry("fishing", "위로 흐르는 물"),
            Map.entry("mining", "광석 벽 한 칸이 내 얼굴"),
            Map.entry("farming", "나를 따라 고개를 돌리는 허수아비"),
            Map.entry("machines", "내가 처음 접속한 시각에 멈춘 시계"),
            Map.entry("jobs", "일곱 번째 직업 「안내원」"));

    private final Plugin plugin;
    private final Haunt haunt;
    private final String workerSkin;

    Pictures(Plugin plugin, Haunt haunt, String workerSkin) {
        this.plugin = plugin;
        this.haunt = haunt;
        this.workerSkin = workerSkin;
    }

    // ------------------------------------------------------------------------------------------
    // station geometry
    // ------------------------------------------------------------------------------------------

    /** A point at station coordinates (block centres at whole lx, lz). */
    static Location at(World w, GuideLayout.Station s, double lx, double ly, double lz) {
        double[] xz = s.point(lx, lz);
        return new Location(w, xz[0], GuideLayout.FLOOR_Y + ly, xz[1]);
    }

    /** The block at station coordinates. */
    static Location blockAt(World w, GuideLayout.Station s, int lx, int ly, int lz) {
        int[] c = s.block(lx, lz);
        return new Location(w, c[0], GuideLayout.FLOOR_Y + ly, c[1]);
    }

    /** Facing someone walking in (back toward the avenue). */
    static float out(GuideLayout.Station s) {
        return s.yaw() + 180;
    }

    static float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static BlockFace face(int x, int z) {
        if (x > 0) return BlockFace.EAST;
        if (x < 0) return BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s);
    }

    // ------------------------------------------------------------------------------------------
    // the thirteen
    // ------------------------------------------------------------------------------------------

    /** Builds a station's wrong picture for this player (null for a station with none). */
    Picture build(Player p, GuideLayout.Station s, World w) {
        Picture pic = new Picture(s.id(), new Props(plugin, haunt, p.getUniqueId()));
        Props x = pic.props;
        String name = esc(p.getName());
        switch (s.id()) {
            case "missions" -> {
                // a name tag over nobody, where someone would stand before the bookshelves
                TextDisplay tag = x.text(at(w, s, 0, 2.1, 9), "<white>" + name, Display.Billboard.CENTER, 1f, true);
                pic.answer = x.hit(at(w, s, 0, 0, 9), 0.6f, 1.9f);
                pic.frame = (q, f) -> {
                    if (f % 70 == 0 && q.getLocation().distanceSquared(tag.getLocation()) < 100)
                        q.playSound(tag.getLocation().clone().subtract(0, 2, 0), Sound.BLOCK_STONE_STEP, 0.35f, 0.9f);
                };
            }
            case "stocks" -> {
                // the board lists them, falling
                Location board = at(w, s, 0, 0.7, 13.45);
                board.setYaw(out(s));
                TextDisplay t = x.text(board, "", Display.Billboard.FIXED, 0.8f, false);
                String top = "<white><b>" + name + "</b>  ";
                t.text(Text.mm(top + "<red>▼ 99.97%\n<dark_gray>거래 정지 · 상장 폐지 예정"));
                pic.answer = x.hit(at(w, s, 0, 0, 13.4), 2.4f, 2.2f);
                pic.frame = (q, f) -> {
                    if (f % 10 == 0) t.text(Text.mm(top + (f % 20 == 0 ? "<red>▼" : "<dark_red>▼") + " 99.97%\n<dark_gray>거래 정지 · 상장 폐지 예정"));
                };
            }
            case "trade" -> {
                // a parcel on the counter, from them, to them
                x.block(at(w, s, 2, 1, 12), Material.BARREL.createBlockData(d -> ((Directional) d).setFacing(BlockFace.UP)), 0.45f, 0.45f, 0.45f, s.yaw());
                x.text(at(w, s, 2, 1.75, 12), "<gray>보내는 사람 <white>" + name + "\n<gray>받는 사람 <white>" + name, Display.Billboard.VERTICAL, 0.5f, false);
                pic.answer = x.hit(at(w, s, 2, 1, 12), 0.6f, 0.55f);
            }
            case "accessories" -> {
                // a mirror behind the pedestals; the one in it is a moment late
                BlockData frameBlock = Material.POLISHED_DEEPSLATE.createBlockData();
                x.block(at(w, s, 0, 3.0, 15.5), frameBlock, 4.4f, 0.2f, 0.2f, s.yaw());
                x.block(at(w, s, -2.1, 0, 15.5), frameBlock, 0.2f, 3.2f, 0.2f, s.yaw());
                x.block(at(w, s, 2.1, 0, 15.5), frameBlock, 0.2f, 3.2f, 0.2f, s.yaw());
                x.block(at(w, s, 0, 0, 15.5), Material.GLASS.createBlockData(), 4.0f, 3.0f, 0.04f, s.yaw());
                Location start = mirror(s, p.getLocation(), w);
                Mannequin twin = x.add(haunt.likeness(p, p, start, null));
                Deque<Location> late = new ArrayDeque<>();
                pic.answer = x.hit(at(w, s, 0, 0, 15.3), 2.0f, 3.0f);
                pic.frame = (q, f) -> {
                    late.addLast(mirror(s, q.getLocation(), w));
                    if (late.size() < 7) return;   // about 1.2 seconds behind
                    Location to = late.removeFirst();
                    if (twin.isValid()) twin.teleport(to);
                };
            }
            case "land" -> {
                // a claim sign in the plot, with their name on it
                Location b = blockAt(w, s, 3, 0, 6);
                BlockData post = Material.OAK_SIGN.createBlockData(d -> ((org.bukkit.block.data.type.Sign) d).setRotation(face(-s.fx(), -s.fz())));
                List<Component> lines = List.of(Text.mm("<b>땅 주인"), Text.mm(name), Text.mm(""),
                        Text.mm("<dark_gray>" + UsherPlan.date(first(p)) + " ~"));
                x.fake(b, post);
                sign(p, b, post, lines);
                pic.answer = x.hit(at(w, s, 3, 0, 6), 0.7f, 1.4f);
                pic.frame = (q, f) -> {
                    if (f % 25 != 0) return;
                    q.sendBlockChange(b, post);
                    sign(q, b, post, lines);
                };
            }
            case "bus" -> {
                // footprints: a few from the way in, then always a step ahead of where they walk
                BlockData mud = Material.MUD.createBlockData();
                Deque<BlockDisplay> prints = new ArrayDeque<>();
                float in = s.yaw();
                for (int i = 0; i < 8; i++) {
                    double side = i % 2 == 0 ? -0.18 : 0.18;
                    prints.addLast(x.block(at(w, s, side, 0.005, 1.2 + i * 0.75), mud, 0.2f, 0.02f, 0.32f, in));
                }
                Location last = prints.getLast().getLocation();
                pic.answer = x.hit(last, 0.8f, 0.3f);
                double[] was = {p.getX(), p.getZ()};
                int[] n = {0};
                pic.frame = (q, f) -> {
                    if (f % 3 != 0 || !s.onPad(q.getX(), q.getZ())) return;
                    double dx = q.getX() - was[0], dz = q.getZ() - was[1];
                    double d = Math.hypot(dx, dz);
                    if (d < 0.75) return;
                    was[0] = q.getX();
                    was[1] = q.getZ();
                    double ux = dx / d, uz = dz / d;
                    double side = n[0]++ % 2 == 0 ? -0.18 : 0.18;
                    Location ahead = new Location(w, q.getX() + ux * 2.2 - uz * side, GuideLayout.FLOOR_Y + 0.005, q.getZ() + uz * 2.2 + ux * side);
                    if (!s.onPad(ahead.getX(), ahead.getZ())) return;
                    prints.addLast(x.block(ahead, mud, 0.2f, 0.02f, 0.32f, (float) Math.toDegrees(Math.atan2(-ux, uz))));
                    while (prints.size() > 16) x.forget(prints.removeFirst());
                    if (pic.answer.isValid()) pic.answer.teleport(ahead);
                };
            }
            case "dock" -> {
                // the inspection clerk stands on his head, and watches
                Location at = at(w, s, 6, 0, 17);
                at.setYaw(out(s));
                Mannequin man = x.add(haunt.figure(p, at, workerSkin, null));
                // upside down: the name the game turns over, on the profile and as a (hidden) name
                upsideDown(man);
                man.customName(Component.text("Dinnerbone"));
                man.setCustomNameVisible(false);
                pic.answer = x.hit(at(w, s, 6, 0, 17), 0.8f, 2.0f);
                pic.frame = (q, f) -> {
                    if (f % 2 == 0 && man.isValid()) man.setRotation(yawTo(man.getLocation(), q.getLocation()), 0);
                };
            }
            case "shop" -> {
                // a chair stuck to the underside of the awning
                BlockData chair = Material.OAK_STAIRS.createBlockData(d -> {
                    Stairs st = (Stairs) d;
                    st.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                    st.setFacing(face(s.fx(), s.fz()));
                });
                x.block(at(w, s, 1, 2, 11), chair, 1f, 1f, 1f, 0);
                pic.answer = x.hit(at(w, s, 1, 2, 11), 1.0f, 1.0f);
            }
            case "fishing" -> {
                // a thin stream from the pond into the air: the water runs up
                x.block(at(w, s, 0, -1.0, 8), Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(), 0.4f, 3.8f, 0.4f, 0);
                Location base = at(w, s, 0, -0.9, 8);
                Particle.DustOptions blue = new Particle.DustOptions(Color.fromRGB(90, 150, 230), 0.9f);
                pic.answer = x.hit(at(w, s, 0, -0.5, 8), 0.9f, 3.4f);
                pic.frame = (q, f) -> {
                    for (int k = 0; k < 4; k++) {
                        double h = (f * 0.3 + k * 0.95) % 3.8;
                        q.spawnParticle(Particle.DUST, base.clone().add(0, h, 0), 1, 0.06, 0, 0.06, 0, blue);
                    }
                    if (f % 4 == 0) q.spawnParticle(Particle.SPLASH, base.clone().add(0, 3.8, 0), 3, 0.15, 0.05, 0.15, 0);
                    if (f % 30 == 0) q.playSound(base, Sound.BLOCK_WATER_AMBIENT, 0.25f, 1.9f);
                };
            }
            case "mining" -> {
                // one ore of the wall is their own face
                Location hole = blockAt(w, s, 1, 1, 12);
                x.fake(hole, Material.AIR.createBlockData());
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                head.editMeta(SkullMeta.class, m -> m.setPlayerProfile(p.getPlayerProfile()));
                x.item(at(w, s, 1, 1.5, 12), head, ItemDisplay.ItemDisplayTransform.HEAD, 1.6f, out(s));
                pic.answer = x.hit(at(w, s, 1, 1, 11.9), 1.0f, 1.0f);
                pic.frame = (q, f) -> {
                    if (f % 25 == 0) q.sendBlockChange(hole, Material.AIR.createBlockData());
                };
            }
            case "farming" -> {
                // a scarecrow by the field; its head turns after them
                x.block(at(w, s, 7, 0, 9), Material.OAK_FENCE.createBlockData(), 1f, 1f, 1f, 0);
                x.block(at(w, s, 7, 0.95, 9), Material.HAY_BLOCK.createBlockData(), 0.6f, 0.62f, 0.6f, s.yaw());
                x.block(at(w, s, 7, 1.3, 9), Material.STRIPPED_OAK_LOG.createBlockData(), 1.6f, 0.13f, 0.13f, s.yaw());
                BlockDisplay head = x.block(at(w, s, 7, 1.6, 9),
                        Material.CARVED_PUMPKIN.createBlockData(d -> ((Directional) d).setFacing(BlockFace.SOUTH)), 0.58f, 0.58f, 0.58f, out(s));
                pic.answer = x.hit(at(w, s, 7, 0, 9), 0.9f, 2.3f);
                pic.frame = (q, f) -> {
                    if (f % 2 == 0 && head.isValid()) head.setRotation(yawTo(head.getLocation(), q.getLocation()), 0);
                };
            }
            case "machines" -> {
                // a time clock by the machine floor, stopped at the minute they first came
                BlockData post = Material.DARK_OAK_FENCE.createBlockData();
                x.block(at(w, s, 0, 0, 18), post, 1f, 1f, 1f, 0);
                x.block(at(w, s, 0, 1, 18), post, 1f, 1f, 1f, 0);
                x.block(at(w, s, 0, 1.75, 18), Material.POLISHED_BLACKSTONE.createBlockData(), 1.3f, 1.3f, 0.2f, s.yaw());
                Location faceAt = at(w, s, 0, 2.15, 17.85);
                faceAt.setYaw(out(s));
                TextDisplay clock = x.text(faceAt, "<#f2ead3><b>" + UsherPlan.clock(first(p)) + "</b>", Display.Billboard.FIXED, 1.7f, false);
                clock.setRotation(out(s), 0);
                pic.answer = x.hit(at(w, s, 0, 0, 18), 1.3f, 3.1f);
            }
            case "jobs" -> {
                // a seventh job beside the six: 안내원
                x.block(at(w, s, 7, 0, 12), Material.LECTERN.createBlockData(d -> ((Directional) d).setFacing(face(-s.fx(), -s.fz()))), 1f, 1f, 1f, 0);
                x.text(at(w, s, 7, 1.6, 12), "<white>안내원", Display.Billboard.CENTER, 1f, false);
                pic.answer = x.hit(at(w, s, 7, 0, 12), 1.1f, 1.6f);
            }
            default -> {
                return null;
            }
        }
        return pic;
    }

    /** A sign's words only one player sees (the sign itself is a fake block there). */
    private static void sign(Player p, Location at, BlockData post, List<Component> lines) {
        if (!(post.createBlockState() instanceof org.bukkit.block.Sign st)) return;
        var side = st.getSide(org.bukkit.block.sign.Side.FRONT);
        for (int i = 0; i < lines.size() && i < 4; i++) side.line(i, lines.get(i));
        p.sendBlockUpdate(at, st);
    }

    /** The worker's skin under the profile name "Dinnerbone". */
    private void upsideDown(Mannequin man) {
        if (workerSkin == null || workerSkin.isBlank()) return;
        boolean slim = workerSkin.endsWith(" slim");
        String k = slim ? workerSkin.substring(0, workerSkin.length() - 5).trim() : workerSkin.trim();
        try {
            man.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile().name("Dinnerbone")
                    .skinPatch(sp -> sp.body(net.kyori.adventure.key.Key.key(k))
                            .model(slim ? org.bukkit.profile.PlayerTextures.SkinModel.SLIM : org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC)).build());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[비밀] 안내원: 직원 스킨 키가 잘못됨: " + workerSkin);
        }
    }

    /** When they first came (ms): the server's record, or now. */
    static long first(Player p) {
        long t = p.getFirstPlayed();
        return t > 0 ? t : System.currentTimeMillis();
    }

    /** Their place as the 장신구 mirror shows it: across the glass (lz 15.5), turned back toward them. */
    private static Location mirror(GuideLayout.Station s, Location l, World w) {
        double[] loc = s.local(l.getX(), l.getZ());
        double mz = Math.min(20.3, 31 - loc[1]);
        Location to = at(w, s, loc[0], 0, mz);
        to.setY(l.getY());
        // the direction mirrored across the glass: the inward part turns back
        double yaw = Math.toRadians(l.getYaw());
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        double lr = dx * s.rx() + dz * s.rz(), li = dx * s.fx() + dz * s.fz();
        double wx = lr * s.rx() - li * s.fx(), wz = lr * s.rz() - li * s.fz();
        to.setYaw((float) Math.toDegrees(Math.atan2(-wx, wz)));
        to.setPitch(l.getPitch());
        return to;
    }

    /** The order of stations (for the preview list). */
    static List<String> describe(List<String> order) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            GuideLayout.Station s = GuideLayout.station(order.get(i));
            out.add((i + 1) + ". " + (s == null ? order.get(i) : s.title()) + " — " + WHAT.getOrDefault(order.get(i), "?"));
        }
        return out;
    }
}
