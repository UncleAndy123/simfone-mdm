package com.simfone.mdm.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.simfone.mdm.json.Application;
import com.simfone.mdm.json.ApplicationSetting;
import com.simfone.mdm.json.ServerConfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConfigSanityTest {
    private static final String SELF = "com.simfone.mdm";

    private static ServerConfig cfg(String restrictions, String hidden, boolean relax) {
        ServerConfig c = new ServerConfig();
        c.setRestrictions(restrictions);
        List<ApplicationSetting> s = new ArrayList<>();
        if (hidden != null) s.add(setting("hidden_packages", hidden));
        if (relax) s.add(setting("intentional_relax", "true"));
        c.setApplicationSettings(s);
        c.setApplications(Collections.singletonList(new Application()));
        return c;
    }

    private static ApplicationSetting setting(String name, String value) {
        ApplicationSetting a = new ApplicationSetting();
        a.setPackageId(SELF);
        a.setName(name);
        a.setValue(value);
        return a;
    }

    @Test public void firstConfigAccepted() {
        assertNull(ConfigSanity.check(SELF, null, cfg("no_safe_boot", null, false)));
    }

    @Test public void emptyConfigRejected() {
        assertNotNull(ConfigSanity.check(SELF, null, new ServerConfig()));
        assertNotNull(ConfigSanity.check(SELF, cfg("a,b", null, false), null));
    }

    @Test public void smallChangeAccepted() {
        assertNull(ConfigSanity.check(SELF, cfg("a,b,c,d", null, false), cfg("a,b,c", null, false)));
    }

    @Test public void massRelaxRejected() {
        assertNotNull(ConfigSanity.check(SELF, cfg("a,b,c,d", null, false), cfg("a", null, false)));
        assertNotNull(ConfigSanity.check(SELF, cfg("a", "p1,p2,p3", false), cfg("a", "p1", false)));
    }

    @Test public void massRelaxWithFlagAccepted() {
        assertNull(ConfigSanity.check(SELF, cfg("a,b,c,d", null, false), cfg("", null, true)));
    }
}
