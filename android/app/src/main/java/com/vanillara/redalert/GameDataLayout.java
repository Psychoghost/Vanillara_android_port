package com.vanillara.redalert;

import java.util.Locale;

/**
 * Decides where an imported file belongs in the layout the engine searches.
 * Pure Java so it can be unit tested on a normal JVM.
 *
 * The engine looks for files only in assets/redalert/<side>/ and
 * assets/redalert/<side>/INSTALL/, using upper-case names. Disc MAIN.MIX files
 * all share one name, so the Allied or Soviet side is taken from the folder
 * names around them. A MAIN.MIX with no hint is reported as unknown and the
 * importer assigns it to the side whose import button was pressed.
 */
public final class GameDataLayout {

    public enum Kind {
        MAIN_ALLIES,
        MAIN_SOVIET,
        MAIN_UNKNOWN,
        MAIN_EXPANSION,
        SHARED_MIX,
        CONFIG_INI,
        SCENARIO_FILE,
        SKIP
    }

    public static final String MAIN_NAME = "MAIN.MIX";
    public static final String CONFIG_NAME = "REDALERT.INI";

    public static final class Decision {
        public final Kind kind;
        public final String name;

        Decision(Kind kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    private GameDataLayout() {
    }

    /**
     * @param folders folder names from the selected root down to the file's parent
     * @param fileName the file's original name
     */
    public static Decision classify(String[] folders, String fileName) {
        String name = GameDataInspector.normalizeSegment(fileName);
        if (name.equals(MAIN_NAME)) {
            return new Decision(mainKind(folders), name);
        }
        if (name.endsWith(".MIX")) {
            return new Decision(Kind.SHARED_MIX, name);
        }
        if (name.equals(CONFIG_NAME)) {
            return new Decision(Kind.CONFIG_INI, name);
        }
        if (name.startsWith("SC") && (name.endsWith(".INI") || name.endsWith(".BIN"))) {
            return new Decision(Kind.SCENARIO_FILE, name);
        }
        return new Decision(Kind.SKIP, name);
    }

    static Kind mainKind(String[] folders) {
        if (folders == null) {
            return Kind.MAIN_UNKNOWN;
        }
        for (int i = folders.length - 1; i >= 0; --i) {
            String folder = folders[i] == null ? "" : folders[i].toLowerCase(Locale.US);
            if (folder.contains("counterstrike") || folder.contains("counter strike")
                    || folder.contains("aftermath") || folder.contains("expansion")) {
                return Kind.MAIN_EXPANSION;
            }
            if (folder.contains("allied") || folder.contains("allies")) {
                return Kind.MAIN_ALLIES;
            }
            if (folder.contains("soviet")) {
                return Kind.MAIN_SOVIET;
            }
        }
        return Kind.MAIN_UNKNOWN;
    }
}
