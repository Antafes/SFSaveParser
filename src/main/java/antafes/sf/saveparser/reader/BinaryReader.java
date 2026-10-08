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

package antafes.sf.saveparser.reader;

import antafes.sf.saveparser.error.SaveParseException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BinaryReader
{
    private final byte[] data;
    private int offset;

    public BinaryReader(byte[] data)
    {
        this.data = data;
    }

    public int offset()
    {
        return this.offset;
    }

    public int remaining()
    {
        return this.data.length - this.offset;
    }

    public void requireEnd()
    {
        if (this.remaining() != 0) {
            throw this.error("Unexpected trailing data: " + this.remaining() + " bytes remain");
        }
    }

    public byte readByte()
    {
        this.require(1, "int8");
        return this.data[this.offset++];
    }

    public int readUnsignedByte()
    {
        return Byte.toUnsignedInt(this.readByte());
    }

    public int readUnsignedShort()
    {
        this.require(2, "uint16");
        int value = Byte.toUnsignedInt(this.data[this.offset]) | Byte.toUnsignedInt(this.data[this.offset + 1]) << 8;
        this.offset += 2;
        return value;
    }

    public int readInt()
    {
        this.require(4, "int32");
        int value = Byte.toUnsignedInt(this.data[this.offset])
            | Byte.toUnsignedInt(this.data[this.offset + 1]) << 8
            | Byte.toUnsignedInt(this.data[this.offset + 2]) << 16
            | this.data[this.offset + 3] << 24;
        this.offset += 4;
        return value;
    }

    public long readUnsignedInt()
    {
        return Integer.toUnsignedLong(this.readInt());
    }

    public long readLong()
    {
        this.require(8, "int64");
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value |= (long) Byte.toUnsignedInt(this.data[this.offset + index]) << (index * 8);
        }
        this.offset += 8;
        return value;
    }

    public float readFloat()
    {
        return Float.intBitsToFloat(this.readInt());
    }

    public double readDouble()
    {
        return Double.longBitsToDouble(this.readLong());
    }

    public boolean readBoolean(String context)
    {
        long value = this.readUnsignedInt();
        if (value != 0 && value != 1) {
            throw this.error("Expected boolean for " + context + " but found " + value);
        }
        return value == 1;
    }

    public boolean readByteBoolean(String context)
    {
        int value = this.readUnsignedByte();
        if (value != 0 && value != 1) {
            throw this.error("Expected byte boolean for " + context + " but found " + value);
        }
        return value == 1;
    }

    public String readString()
    {
        int length = this.readInt();
        if (length == 0) {
            return "";
        }
        if (length == Integer.MIN_VALUE) {
            throw this.error("Invalid Unreal string length");
        }
        if (length > 0) {
            this.require(length, "ANSI string");
            if (this.data[this.offset + length - 1] != 0) {
                throw this.error("ANSI string is missing a terminator");
            }
            String value = new String(this.data, this.offset, length - 1, StandardCharsets.UTF_8);
            this.offset += length;
            return value;
        }

        int byteLength = Math.multiplyExact(Math.negateExact(length), Character.BYTES);
        this.require(byteLength, "UTF-16 string");
        if (this.data[this.offset + byteLength - 1] != 0 || this.data[this.offset + byteLength - 2] != 0) {
            throw this.error("UTF-16 string is missing a terminator");
        }
        String value = new String(this.data, this.offset, byteLength - Character.BYTES, StandardCharsets.UTF_16LE);
        this.offset += byteLength;
        return value;
    }

    public byte[] readBytes(int length)
    {
        this.require(length, "data");
        byte[] value = Arrays.copyOfRange(this.data, this.offset, this.offset + length);
        this.offset += length;
        return value;
    }

    public void skip(int length)
    {
        this.require(length, "data");
        this.offset += length;
    }

    public void expectUnsignedInt(long expected, String context)
    {
        long actual = this.readUnsignedInt();
        if (actual != expected) {
            throw this.error("Expected " + context + " to be " + expected + " but found " + actual);
        }
    }

    public void expectUnsignedByte(int expected, String context)
    {
        int actual = this.readUnsignedByte();
        if (actual != expected) {
            throw this.error("Expected " + context + " to be " + expected + " but found " + actual);
        }
    }

    public void expectString(String expected, String context)
    {
        String actual = this.readString();
        if (!actual.equals(expected)) {
            throw this.error("Expected " + context + " to be '" + expected + "' but found '" + actual + "'");
        }
    }

    public SaveParseException error(String message)
    {
        return new SaveParseException(message + " at byte offset " + this.offset);
    }

    private void require(int length, String type)
    {
        if (length < 0 || length > this.remaining()) {
            throw this.error("Cannot read " + type + " of length " + length + " from " + this.remaining() + " remaining bytes");
        }
    }
}
