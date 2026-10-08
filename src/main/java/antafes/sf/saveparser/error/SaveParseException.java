/*
 * This file is part of SFSaveParser.
 *
 * SFSaveParser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SFSaveParser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SFSaveParser. If not, see <http://www.gnu.org/licenses/>.
 *
 * @package SFSaveParser
 * @author Marian Pollzien <map@wafriv.de>
 * @copyright (c) 2026, Marian Pollzien
 * @license https://www.gnu.org/licenses/lgpl.html LGPLv3
 */

package antafes.sf.saveparser.error;

/**
 * Signals that a Satisfactory save cannot be decoded safely.
 */
public class SaveParseException extends RuntimeException
{
    public SaveParseException(String message)
    {
        super(message);
    }

    public SaveParseException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
