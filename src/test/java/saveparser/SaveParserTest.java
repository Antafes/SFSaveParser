/*
 * This file is part of SFManager.
 *
 * SFManager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package saveparser;

import antafes.sf.saveparser.error.SaveParseException;
import antafes.sf.saveparser.SaveParser;
import antafes.sf.saveparser.SaveParserService;
import antafes.sf.saveparser.dto.ParsedSave;
import antafes.sf.saveparser.dto.SaveFileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(SaveParserTest.ParserConfiguration.class)
class SaveParserTest
{
    @Autowired
    private SaveParserService saveParser;

    @Test
    void readsSaveVersion36Header() throws IOException, URISyntaxException
    {
        SaveFileInfo fileInfo = this.saveParser.readSaveFileInfo(this.fixturePath(36));

        assertEquals(10, fileInfo.headerVersion());
        assertEquals(36, fileInfo.saveVersion());
        assertEquals(211_839, fileInfo.buildVersion());
        assertEquals("", fileInfo.saveName());
        assertEquals("Persistent_Level", fileInfo.mapName());
        assertEquals("test", fileInfo.sessionName());
    }

    @Test
    void parsesSaveVersion36Completely() throws IOException, URISyntaxException
    {
        ParsedSave save = this.saveParser.readFullSaveFile(this.fixturePath(36));

        assertNotNull(save.fileInfo());
        assertEquals(59, save.levels().size());
        assertFalse(save.levels().getLast().objects().isEmpty());
    }

    @Test
    void readsSaveVersion60Header() throws IOException, URISyntaxException
    {
        SaveFileInfo fileInfo = this.saveParser.readSaveFileInfo(this.fixturePath(60));

        assertEquals(60, fileInfo.saveVersion());
    }

    @Test
    void parsesSaveVersion60Completely() throws IOException, URISyntaxException
    {
        ParsedSave save = this.saveParser.readFullSaveFile(this.fixturePath(60));

        assertNotNull(save.fileInfo());
        assertFalse(save.levels().isEmpty());
        assertFalse(save.levels().getLast().objects().isEmpty());
    }

    @Test
    void readsModdedSaveHeader() throws IOException, URISyntaxException
    {
        SaveFileInfo fileInfo = this.saveParser.readSaveFileInfo(this.fixturePath("test-big-mods"));

        assertTrue(fileInfo.modded());
        assertFalse(fileInfo.modMetadata().isEmpty());
    }

    @Test
    void parsesModdedSaveCompletely() throws IOException, URISyntaxException
    {
        ParsedSave save = this.saveParser.readFullSaveFile(this.fixturePath("test-big-mods"));

        assertNotNull(save.fileInfo());
        assertFalse(save.levels().isEmpty());
        assertFalse(save.levels().getLast().objects().isEmpty());
    }

    @Test
    void rejectsUnsupportedHeaderVersion() throws IOException
    {
        byte[] invalidSave = {9, 0, 0, 0};

        SaveParseException exception = assertThrows(
            SaveParseException.class,
            () -> this.saveParser.readSaveFileInfo(new ByteArrayInputStream(invalidSave))
        );

        assertEquals("Unsupported save header version 9 at byte offset 4", exception.getMessage());
    }

    private Path fixturePath(int saveVersion) throws URISyntaxException
    {
        return this.fixturePath("test-" + saveVersion);
    }

    private Path fixturePath(String name) throws URISyntaxException
    {
        return Path.of(getClass().getResource("/data/" + name + ".sav").toURI());
    }

    @Configuration
    @ComponentScan(basePackageClasses = SaveParser.class)
    static class ParserConfiguration
    {
    }
}
