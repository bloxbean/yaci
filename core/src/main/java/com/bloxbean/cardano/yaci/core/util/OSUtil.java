package com.bloxbean.cardano.yaci.core.util;

public class OSUtil {
    public enum OS {
        WINDOWS, LINUX, MAC, SOLARIS
    };

    public static OS getOperatingSystem() {
        // detecting the operating system using `os.name` System property
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return OS.WINDOWS;
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return OS.LINUX;
        } else if (os.contains("mac")) {
            return OS.MAC;
        } else if (os.contains("sunos")) {
            return OS.SOLARIS;
        }

        return null;
    }
}
