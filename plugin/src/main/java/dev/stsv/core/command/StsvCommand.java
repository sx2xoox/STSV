package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
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

    private static final List<String> SUBS = List.of("give", "browse", "list", "info", "reload", "pack");
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
            default -> sender.sendMessage(Text.msg("<gray>/" + label + " <white>give <플레이어> <아이템> [개수]</white> | "
                    + "<white>browse</white> | <white>list</white> | <white>info</white> | <white>reload</white> | <white>pack</white>"));
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

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
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
