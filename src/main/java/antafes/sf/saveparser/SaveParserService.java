/*
 * This file is part of SFManager.
 *
 * SFManager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SFManager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SFManager. If not, see <http://www.gnu.org/licenses/>.
 *
 * @package SFManager
 * @author Marian Pollzien <map@wafriv.de>
 * @copyright (c) 2026, Marian Pollzien
 * @license https://www.gnu.org/licenses/lgpl.html LGPLv3
 */

package antafes.sf.saveparser;

import antafes.sf.saveparser.dto.ParsedSave;
import antafes.sf.saveparser.dto.SaveFileInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Public contract for reading Satisfactory save files.
 */
public interface SaveParserService
{
    /**
     * Reads only uncompressed metadata from a file.
     *
     * @param path save file path
     * @return parsed file metadata
     * @throws IOException if the file cannot be read
     */
    SaveFileInfo readSaveFileInfo(Path path) throws IOException;

    /**
     * Reads only uncompressed metadata from a save stream.
     *
     * @param inputStream save file stream
     * @return parsed file metadata
     * @throws IOException if the stream cannot be read
     */
    SaveFileInfo readSaveFileInfo(InputStream inputStream) throws IOException;

    /**
     * Reads an entire save file.
     *
     * @param path save file path
     * @return complete parsed save
     * @throws IOException if the file cannot be read
     */
    ParsedSave readFullSaveFile(Path path) throws IOException;

    /**
     * Reads an entire save stream.
     *
     * @param inputStream save file stream
     * @return complete parsed save
     * @throws IOException if the stream cannot be read
     */
    ParsedSave readFullSaveFile(InputStream inputStream) throws IOException;
}
