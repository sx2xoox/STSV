package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.gui.RecipeEditor;
import dev.stsv.core.recipe.RecipeModule;
import org.bukkit.NamespacedKey;
import dev.stsv.core.pack.PackBuilder;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class StsvCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("give", "browse", "list", "info", "reload", "pack", "recipe", "fuel", "skin", "cosmetic", "wardrobe");
    private final STSVPlugin plugin;

    public StsvCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> give(sender, args);
            case "browse" -> {
                if (sender instanceof Player p) plugin.itemBrowser().open(p, 0);
                else sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
            }
            case "list" -> {
                sender.sendMessage(Text.msg("<gray>커스텀 아이템 <white>" + plugin.content().items().size() + "</white>개"));
                for (CustomItem i : plugin.content().items()) {
                    sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + i.key().asString()
                            + "</white>" + (i.isFurniture() ? " <gold>[가구]" : "")));
                }
            }
            case "info" -> {
                if (!(sender instanceof Player p)) return true;
                Optional<CustomItem> item = plugin.content().itemOf(p.getInventory().getItemInMainHand());
                sender.sendMessage(item.map(i -> Text.msg("<white>" + i.key().asString() + " <gray>(팩: " + i.pack().id()
                                + ", 모델: " + (i.itemModelKey() == null ? "없음" : i.itemModelKey().asString()) + ")"))
                        .orElse(Text.msg("<gray>손에 든 아이템은 커스텀 아이템이 아닙니다.")));
            }
            case "reload" -> {
                long t = System.currentTimeMillis();
                plugin.reloadConfig();
                List<String> failed = plugin.modules().reloadAll();
                if (failed.isEmpty()) {
                    sender.sendMessage(Text.msg("<green>리로드 완료 <gray>(" + (System.currentTimeMillis() - t) + "ms)"));
                } else {
                    sender.sendMessage(Text.msg("<red>일부 모듈 리로드 실패: " + String.join(", ", failed) + " <gray>(콘솔 확인)"));
                }
            }
            case "pack" -> {
                PackBuilder.Built b = plugin.pack().current();
                if (b != null) {
                    sender.sendMessage(Text.msg("<gray>리소스팩: 파일 " + b.files() + "개, " + b.zip().length / 1024
                            + "KB, 경고 " + b.warnings().size() + "개"));
                }
                if (sender instanceof Player p) plugin.pack().send(p);
            }
            case "recipe" -> recipe(sender, args);
            case "skin" -> {
                if (sender instanceof Player p && args.length > 1 && args[1].equalsIgnoreCase("remove")) plugin.skins().removeHeld(p);
                else sender.sendMessage(Text.msg("<red>/stsv skin remove <gray>(손에 든 아이템의 스킨 해제)"));
            }
            case "cosmetic" -> cosmetic(sender, args);
            case "wardrobe" -> wardrobe(sender, args);
            case "fuel" -> fuel(sender, args);
            default -> {
                sender.sendMessage(Text.msg("<gray>명령어 목록"));
                for (String line : List.of(
                        "give <플레이어> <아이템> [개수] <dark_gray>- 아이템 지급",
                        "browse <dark_gray>- 아이템 목록 GUI",
                        "list | info <dark_gray>- 아이템 목록 / 손에 든 아이템 정보",
                        "recipe create <id> <종류> [시간] [경험치] <dark_gray>- 레시피 편집기",
                        "recipe delete <id> | recipe list",
                        "fuel set <틱> | fuel remove | fuel list <dark_gray>- 손에 든 아이템의 화로 연료 시간",
                        "skin remove <dark_gray>- 손에 든 아이템의 스킨 해제",
                        "cosmetic give|take <플레이어> <치장> | cosmetic info [플레이어] <dark_gray>- 치장 지급/회수/상태",
                        "wardrobe create <이름> [반경] | delete <이름> | list <dark_gray>- 옷장 구역",
                        "reload | pack")) {
                    sender.sendMessage(Text.mm(" <white>/" + label + " " + line));
                }
            }
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.msg("<red>/stsv give <플레이어> <아이템> [개수]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.msg("<red>플레이어를 찾을 수 없습니다: " + args[1]));
            return;
        }
        Optional<CustomItem> item = plugin.content().item(args[2]);
        if (item.isEmpty()) {
            sender.sendMessage(Text.msg("<red>알 수 없는 아이템: " + args[2]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.clamp(Integer.parseInt(args[3]), 1, 6400);
            } catch (NumberFormatException e) {
                sender.sendMessage(Text.msg("<red>개수는 숫자여야 합니다."));
                return;
            }
        }
        int left = amount;
        while (left > 0) {
            ItemStack stack = item.get().create(1);
            int n = Math.min(left, stack.getMaxStackSize());
            stack.setAmount(n);
            target.getInventory().addItem(stack).values()
                    .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
            left -= n;
        }
        sender.sendMessage(Text.msg("<white>" + target.getName() + "</white><gray>에게 </gray><white>"
                + item.get().key().asString() + " x" + amount + "</white><gray> 지급"));
    }

    // ------------------------------------------------------------------------------------------
    // /stsv recipe ...
    // ------------------------------------------------------------------------------------------

    private NamespacedKey key(String id) {
        String ns = plugin.getConfig().getString("editor.namespace", "stsv");
        NamespacedKey key = id.contains(":") ? NamespacedKey.fromString(id.toLowerCase(Locale.ROOT))
                : NamespacedKey.fromString(ns + ":" + id.toLowerCase(Locale.ROOT));
        if (key == null) throw new IllegalArgumentException("id는 영문 소문자, 숫자, _ 만 가능: " + id);
        return key;
    }

    private void recipe(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            switch (action) {
                case "create" -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
                        return;
                    }
                    if (args.length < 4) {
                        sender.sendMessage(Text.msg("<red>/stsv recipe create <id> <" + String.join("|", RecipeEditor.TYPES)
                                + "> [시간(틱)] [경험치]"));
                        return;
                    }
                    Integer time = args.length > 4 ? Integer.valueOf(args[4]) : null;
                    Float xp = args.length > 5 ? Float.valueOf(args[5]) : null;
                    plugin.recipeEditor().open(p, key(args[2]), args[3], time, xp);
                }
                case "delete" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Text.msg("<red>/stsv recipe delete <id>"));
                        return;
                    }
                    NamespacedKey k = key(args[2]);
                    sender.sendMessage(plugin.recipes().deleteRecipe(k)
                            ? Text.msg("<green>" + k.asString() + " 삭제 완료")
                            : Text.msg("<red>편집기로 만든 레시피(recipes/" + RecipeModule.EDITOR_FILE
                                    + ")만 삭제할 수 있습니다. 다른 파일은 직접 지우고 /stsv reload 하세요."));
                }
                case "list" -> {
                    List<NamespacedKey> keys = plugin.recipes().registered();
                    sender.sendMessage(Text.msg("<gray>등록된 레시피 <white>" + keys.size() + "</white>개"));
                    keys.forEach(k -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + k.asString())));
                }
                default -> sender.sendMessage(Text.msg("<red>/stsv recipe <create|delete|list>"));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>시간과 경험치는 숫자여야 합니다."));
        } catch (Exception e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // /stsv cosmetic | wardrobe
    // ------------------------------------------------------------------------------------------

    private void cosmetic(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
            Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendMessage(Text.msg("<red>/stsv cosmetic info [플레이어]"));
                return;
            }
            sender.sendMessage(Text.msg("<gray>" + target.getName() + " 치장 상태"));
            plugin.cosmetics().info(target).forEach(l -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + l)));
            return;
        }
        if (args.length < 4 || !(args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take"))) {
            sender.sendMessage(Text.msg("<red>/stsv cosmetic <give|take> <플레이어> <치장>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        Optional<CustomItem> c = plugin.content().item(args[3]).filter(i -> i.cosmetic() != null);
        if (target == null || c.isEmpty()) {
            sender.sendMessage(Text.msg("<red>플레이어 또는 치장을 찾을 수 없습니다."));
            return;
        }
        boolean give = args[1].equalsIgnoreCase("give");
        plugin.cosmetics().grant(target, c.get(), give);
        sender.sendMessage(Text.msg("<green>" + target.getName() + " 에게 " + c.get().key().asString() + (give ? " 지급" : " 회수")));
    }

    private void wardrobe(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            switch (action) {
                case "create" -> {
                    if (!(sender instanceof Player p) || args.length < 3) {
                        sender.sendMessage(Text.msg("<red>/stsv wardrobe create <이름> [반경=3] <gray>(지금 서 있는 곳 중심)"));
                        return;
                    }
                    double r = args.length > 3 ? Double.parseDouble(args[3]) : 3;
                    plugin.cosmetics().createZone(args[2], p.getLocation(), r);
                    sender.sendMessage(Text.msg("<green>옷장 구역 '" + args[2] + "' 생성 (반경 " + r + ")"));
                }
                case "delete" -> sender.sendMessage(args.length > 2 && plugin.cosmetics().deleteZone(args[2])
                        ? Text.msg("<green>삭제 완료") : Text.msg("<red>그런 구역이 없습니다."));
                case "list" -> {
                    sender.sendMessage(Text.msg("<gray>옷장 구역 " + plugin.cosmetics().zones().size() + "개"));
                    plugin.cosmetics().zones().forEach((n, b) -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + n
                            + "</white> <gray>" + (int) b.getCenterX() + ", " + (int) b.getCenterY() + ", " + (int) b.getCenterZ())));
                }
                default -> sender.sendMessage(Text.msg("<red>/stsv wardrobe <create|delete|list>"));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>반경은 숫자여야 합니다."));
        } catch (Exception e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // /stsv fuel ...
    // ------------------------------------------------------------------------------------------

    private void fuel(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            if (action.equals("list")) {
                var fuels = plugin.recipes().fuels();
                sender.sendMessage(Text.msg("<gray>커스텀 연료 <white>" + fuels.size() + "</white>개 (석탄 = 1600틱)"));
                fuels.forEach((id, t) -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + id
                        + "</white> <gray>" + t + "틱 (" + (t / 20.0) + "초, 아이템 " + String.format("%.1f", t / 200.0) + "개 굽기)")));
                return;
            }
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.isEmpty()) {
                sender.sendMessage(Text.msg("<red>연료로 쓸 아이템을 손에 드세요."));
                return;
            }
            String id = plugin.recipes().idOf(hand);
            String ns = plugin.content().itemOf(hand).map(i -> i.key().getNamespace())
                    .orElse(plugin.getConfig().getString("editor.namespace", "stsv"));
            switch (action) {
                case "set" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Text.msg("<red>/stsv fuel set <틱>  (석탄 = 1600, 막대기 = 100)"));
                        return;
                    }
                    int ticks = Integer.parseInt(args[2]);
                    if (ticks <= 0) throw new IllegalArgumentException("1 이상이어야 합니다.");
                    if (!hand.getType().isFuel()) {
                        sender.sendMessage(Text.msg("<red>" + id + " 은(는) 기본 재료(" + hand.getType().getKey().getKey()
                                + ")가 연료가 아니라 화로 연료 칸에 들어가지 않습니다. 커스텀 아이템이라면 material 을 coal 등으로 바꾸세요."));
                        return;
                    }
                    plugin.recipes().setFuel(id, ticks, ns);
                    sender.sendMessage(Text.msg("<green>" + id + " 연료 시간 = " + ticks + "틱 (" + ticks / 20.0 + "초)"));
                }
                case "remove" -> {
                    plugin.recipes().setFuel(id, null, ns);
                    sender.sendMessage(Text.msg("<green>" + id + " 연료 설정 삭제 (아이템 정의의 fuel: 은 그대로)"));
                }
                default -> sender.sendMessage(Text.msg("<red>/stsv fuel <set|remove|list>"));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>틱은 숫자여야 합니다."));
        } catch (Exception e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        if (args[0].equalsIgnoreCase("recipe")) {
            if (args.length == 2) return filter(List.of("create", "delete", "list"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                return filter(plugin.recipes().registered().stream().map(NamespacedKey::asString).toList(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("create")) return filter(RecipeEditor.TYPES, args[3]);
        }
        if (args[0].equalsIgnoreCase("fuel") && args.length == 2) return filter(List.of("set", "remove", "list"), args[1]);
        if (args[0].equalsIgnoreCase("skin") && args.length == 2) return filter(List.of("remove"), args[1]);
        if (args[0].equalsIgnoreCase("wardrobe") && args.length == 2) return filter(List.of("create", "delete", "list"), args[1]);
        if (args[0].equalsIgnoreCase("cosmetic")) {
            if (args.length == 2) return filter(List.of("give", "take", "info"), args[1]);
            if (args.length == 3) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
            if (args.length == 4) return filter(plugin.cosmetics().cosmetics().stream().map(i -> i.key().asString()).toList(), args[3]);
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            if (args.length == 3) return filter(plugin.content().items().stream().map(i -> i.key().asString()).toList(), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p) || o.contains(":" + p)).toList();
    }
}
