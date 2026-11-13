package dev.stsv.core.banner;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerAccessTest {

    static Player player(boolean op, boolean permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) -> switch (m.getName()) {
            case "isOp" -> op;
            case "hasPermission" -> permission && BannerModule.PERMISSION.equals(args[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test
    void onlyAdminsMayUseBanners() {
        assertTrue(BannerModule.allowed(player(true, false)), "op");
        assertTrue(BannerModule.allowed(player(false, true)), "stsv.title.banner");
        assertFalse(BannerModule.allowed(player(false, false)), "everyone else");
    }

    @Test
    void permissionIsOpOnlyByDefault() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/plugin.yml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(java.util.regex.Pattern.compile("\n  " + java.util.regex.Pattern.quote(BannerModule.PERMISSION)
                + ":\n    description: [^\n]+\n    default: op\n").matcher(yml).find());
    }

    @Test
    void bracketsComeOffTheTitle() {
        assertEquals("단골", BannerModule.bare("[단골]"));
        assertEquals("최고 부자", BannerModule.bare(" [최고 부자] "));
        assertEquals("거상", BannerModule.bare("거상"));
        assertEquals("[]", BannerModule.bare("[]"));
    }
}
