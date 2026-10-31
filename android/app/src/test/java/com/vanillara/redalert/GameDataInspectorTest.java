package com.vanillara.redalert;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class GameDataInspectorTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void emptyFolderIsNotPlayable() throws Exception {
        File side = temporary.newFolder("allies");
        GameDataInspector.Status status = GameDataInspector.inspect(side);
        assertFalse(status.hasMix);
        assertFalse(status.hasMainMix);
        assertFalse(status.isPlayable());
        assertEquals(0, status.fileCount);
    }

    @Test
    public void mainMixMakesSidePlayable() throws Exception {
        File side = temporary.newFolder("allies");
        File install = new File(side, "INSTALL");
        assertTrue(install.mkdirs());
        write(new File(install, "REDALERT.INI"), "[Options]\n");
        write(new File(side, "REDALERT.MIX"), "mix");
        write(new File(side, "CONQUER.MIX"), "mix");

        GameDataInspector.Status status = GameDataInspector.inspect(side);
        assertTrue(status.hasMix);
        assertTrue(status.hasMainMix);
        assertTrue(status.hasConfig);
        assertTrue(status.isPlayable());
        assertEquals(3, status.fileCount);
    }

    @Test
    public void unrelatedMixIsNotPlayable() throws Exception {
        File side = temporary.newFolder("allies");
        write(new File(side, "MOVIES1.MIX"), "mix");
        GameDataInspector.Status status = GameDataInspector.inspect(side);
        assertTrue(status.hasMix);
        assertFalse(status.hasMainMix);
        assertFalse(status.isPlayable());
    }

    @Test
    public void normalizeSegmentUppercasesAndRemovesSeparators() {
        assertEquals("REDALERT.MIX", GameDataInspector.normalizeSegment("redalert.mix"));
        assertEquals("A_B", GameDataInspector.normalizeSegment("a/b"));
        assertEquals("_", GameDataInspector.normalizeSegment(".."));
        assertEquals("_", GameDataInspector.normalizeSegment("  "));
    }

    @Test
    public void discImagesAndArchivesAreBlocked() {
        assertTrue(GameDataInspector.isBlockedFile("game.iso"));
        assertTrue(GameDataInspector.isBlockedFile("allies.bin"));
        assertTrue(GameDataInspector.isBlockedFile("backup.zip"));
        assertFalse(GameDataInspector.isBlockedFile("REDALERT.MIX"));
        assertFalse(GameDataInspector.isBlockedFile("INSTALL"));
    }

    @Test
    public void ensureConfigMirrorsRootIni() throws Exception {
        File side = temporary.newFolder("allies");
        write(new File(side, "REDALERT.INI"), "[Options]\nGameSpeed=5\n");

        assertTrue(GameDataInspector.ensureConfig(side));
        assertTrue(new File(side, "INSTALL/REDALERT.INI").isFile());
        assertTrue(GameDataInspector.inspect(side).hasConfig);
        assertFalse(GameDataInspector.ensureConfig(side));
    }

    @Test
    public void ensureConfigCreatesMinimalIni() throws Exception {
        File side = temporary.newFolder("soviet");
        assertTrue(GameDataInspector.ensureConfig(side));
        File config = new File(side, "INSTALL/REDALERT.INI");
        assertTrue(config.isFile());
        assertTrue(config.length() > 0);
    }

    @Test
    public void deleteTreeRemovesNestedData() throws Exception {
        File root = temporary.newFolder("redalert-root");
        File nested = new File(root, "assets/redalert/allies");
        assertTrue(nested.mkdirs());
        write(new File(nested, "REDALERT.MIX"), "mix");
        GameDataInspector.deleteTree(root);
        assertFalse(root.exists());
    }

    private static void write(File file, String contents) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents.getBytes("UTF-8"));
        }
    }
}
