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

import antafes.sf.saveparser.dto.*;
import antafes.sf.saveparser.error.SaveParseException;
import antafes.sf.saveparser.reader.BinaryReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Stateless Spring service for reading Satisfactory save files.
 */
@Service
public class SaveParser implements SaveParserService
{
    private static final long LEGACY_HEADER_VERSION = 10;
    private static final long CURRENT_HEADER_VERSION = 14;
    private static final long CHUNK_SIGNATURE = 0x9e2a83c1L;
    private static final long CHUNK_VERSION = 0x22222222L;
    private static final long CHUNK_COMPRESSION_METHOD = 0x03000000L;
    private static final long TICKS_PER_SECOND = 10_000_000L;
    private static final long EPOCH_1_TO_1970_SECONDS = 719_162L * 24 * 60 * 60;

    /**
     * Reads only uncompressed metadata from a file.
     *
     * @param path save file path
     * @return parsed file metadata
     * @throws IOException if the file cannot be read
     */
    @Override
    public SaveFileInfo readSaveFileInfo(Path path) throws IOException
    {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return this.readSaveFileInfo(inputStream);
        }
    }

    /**
     * Reads only uncompressed metadata from a save stream.
     *
     * @param inputStream save file stream
     * @return parsed file metadata
     * @throws IOException if the stream cannot be read
     */
    @Override
    public SaveFileInfo readSaveFileInfo(InputStream inputStream) throws IOException
    {
        return this.parseHeader(new BinaryReader(inputStream.readAllBytes()));
    }

    /**
     * Reads an entire save file.
     *
     * @param path save file path
     * @return complete parsed save
     * @throws IOException if the file cannot be read
     */
    @Override
    public ParsedSave readFullSaveFile(Path path) throws IOException
    {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return this.readFullSaveFile(inputStream);
        }
    }

    /**
     * Reads an entire save stream.
     *
     * @param inputStream save file stream
     * @return complete parsed save
     * @throws IOException if the stream cannot be read
     */
    @Override
    public ParsedSave readFullSaveFile(InputStream inputStream) throws IOException
    {
        BinaryReader fileReader = new BinaryReader(inputStream.readAllBytes());
        SaveFileInfo fileInfo = this.parseHeader(fileReader);
        BinaryReader saveReader = new BinaryReader(this.decompress(fileReader, fileInfo.headerVersion() == LEGACY_HEADER_VERSION));
        int declaredSize = fileInfo.headerVersion() == LEGACY_HEADER_VERSION
            ? this.readCount(saveReader, "uncompressed save size")
            : this.toLength(saveReader.readLong(), "uncompressed save size", saveReader);
        if (declaredSize > saveReader.remaining() + Integer.BYTES) {
            throw saveReader.error("Declared uncompressed save size exceeds decompressed payload");
        }

        SaveObjectVersionData persistentVersionData = null;
        long persistentUnrealEngine5Version = -1;
        if (fileInfo.saveVersion() >= 53) {
            persistentVersionData = this.readVersionData(saveReader);
            persistentUnrealEngine5Version = persistentVersionData.unrealEngine5Version();
        }

        List<SavePartition> partitions = fileInfo.saveVersion() >= 53
            ? this.readPartitions(saveReader)
            : List.of();
        int levelCount = this.readCount(saveReader, "level count");
        List<SaveLevel> levels = new ArrayList<>(levelCount + 1);
        for (int index = 0; index < levelCount; index++) {
            levels.add(this.readLevel(saveReader, fileInfo.saveVersion(), -1, false));
        }
        levels.add(this.readLevel(saveReader, fileInfo.saveVersion(), persistentUnrealEngine5Version, true));

        List<ObjectReference> dropPodReferences = new ArrayList<>();
        List<ObjectReference> extraReferences = new ArrayList<>();
        String finalLevelName;
        if (fileInfo.headerVersion() == LEGACY_HEADER_VERSION) {
            saveReader.expectUnsignedInt(0, "legacy save trailer");
            saveReader.expectUnsignedInt(0, "legacy save trailer");
            finalLevelName = "";
        } else {
            finalLevelName = saveReader.readString();
            if ("Persistent_Level".equals(finalLevelName)) {
                this.readReferences(saveReader, dropPodReferences, "drop pod reference");
                if (saveReader.remaining() > 0) {
                    this.readReferences(saveReader, extraReferences, "extra reference");
                }
            }
        }
        saveReader.requireEnd();
        return new ParsedSave(
            fileInfo,
            persistentVersionData,
            partitions,
            levels,
            finalLevelName,
            dropPodReferences,
            extraReferences
        );
    }

    private SaveFileInfo parseHeader(BinaryReader reader)
    {
        long headerVersion = reader.readUnsignedInt();
        if (headerVersion != LEGACY_HEADER_VERSION && headerVersion != CURRENT_HEADER_VERSION) {
            throw reader.error("Unsupported save header version " + headerVersion);
        }
        long saveVersion = reader.readUnsignedInt();
        if (saveVersion != 36 && saveVersion != 52 && saveVersion != 53 && saveVersion != 58 && saveVersion != 59 && saveVersion != 60) {
            throw reader.error("Unsupported save version " + saveVersion);
        }
        long buildVersion = reader.readUnsignedInt();
        String saveName = headerVersion >= CURRENT_HEADER_VERSION ? reader.readString() : "";
        String mapName = reader.readString();
        String mapOptions = reader.readString();
        String sessionName = reader.readString();
        long playDuration = reader.readUnsignedInt();
        long ticks = reader.readLong();
        Instant saveTime;
        try {
            saveTime = Instant.ofEpochSecond(ticks / TICKS_PER_SECOND - EPOCH_1_TO_1970_SECONDS);
        } catch (RuntimeException exception) {
            throw reader.error("Invalid save timestamp " + ticks);
        }
        int sessionVisibility = reader.readByte();
        Long editorObjectVersion = saveVersion >= 7 ? reader.readUnsignedInt() : null;
        String modMetadata = saveVersion >= 8 ? reader.readString() : "";
        boolean modded = reader.readBoolean("modded save");
        String saveIdentifier = saveVersion >= 10 ? reader.readString() : "";
        List<Long> saveDataHash = List.of();
        Boolean creativeModeEnabled = null;
        if (headerVersion >= CURRENT_HEADER_VERSION && saveVersion >= 13) {
            reader.expectUnsignedInt(1, "partitioned world flag");
            reader.expectUnsignedInt(1, "partitioned world format flag");
            saveDataHash = List.of(reader.readLong(), reader.readLong());
            creativeModeEnabled = reader.readBoolean("creative mode");
        }
        return new SaveFileInfo(
            headerVersion,
            saveVersion,
            buildVersion,
            saveName,
            mapName,
            mapOptions,
            sessionName,
            playDuration,
            saveTime,
            sessionVisibility,
            editorObjectVersion,
            modMetadata,
            modded,
            saveIdentifier,
            saveDataHash,
            creativeModeEnabled
        );
    }

    private byte[] decompress(BinaryReader reader, boolean legacyFormat)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (reader.remaining() > 0) {
            reader.expectUnsignedInt(CHUNK_SIGNATURE, "compression chunk signature");
            if (legacyFormat) {
                reader.expectUnsignedInt(0, "legacy compression chunk version");
                reader.readUnsignedInt();
                int compressedLength = this.readLegacyLength(reader, "compressed chunk length");
                int uncompressedLength = this.readLegacyLength(reader, "uncompressed chunk length");
                int mirroredCompressedLength = this.readLegacyLength(reader, "mirrored compressed chunk length");
                int mirroredUncompressedLength = this.readLegacyLength(reader, "mirrored uncompressed chunk length");
                if (compressedLength != mirroredCompressedLength || uncompressedLength != mirroredUncompressedLength) {
                    throw reader.error("Legacy compression chunk length mirrors do not match");
                }
                reader.expectUnsignedInt(0, "legacy compression chunk trailer");
                output.writeBytes(this.inflate(reader.readBytes(compressedLength), uncompressedLength));
                continue;
            }
            reader.expectUnsignedInt(CHUNK_VERSION, "compression chunk version");
            reader.expectUnsignedByte(0, "compression chunk method");
            reader.readUnsignedInt();
            reader.expectUnsignedInt(CHUNK_COMPRESSION_METHOD, "compression method");
            int compressedLength = this.toLength(reader.readLong(), "compressed chunk length", reader);
            int uncompressedLength = this.toLength(reader.readLong(), "uncompressed chunk length", reader);
            long mirroredCompressedLength = reader.readLong();
            long mirroredUncompressedLength = reader.readLong();
            if (compressedLength != mirroredCompressedLength || uncompressedLength != mirroredUncompressedLength) {
                throw reader.error("Compression chunk length mirrors do not match");
            }
            byte[] compressed = reader.readBytes(compressedLength);
            output.writeBytes(this.inflate(compressed, uncompressedLength));
        }
        return output.toByteArray();
    }

    private int readLegacyLength(BinaryReader reader, String context)
    {
        long highWord = reader.readUnsignedInt();
        long lowWord = reader.readUnsignedInt();
        if (highWord != 0) {
            throw reader.error("Unsupported " + context + " larger than 4 GiB");
        }
        return this.toLength(lowWord, context, reader);
    }

    private byte[] inflate(byte[] compressed, int expectedLength)
    {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] decompressed = new byte[expectedLength];
        try {
            int length = inflater.inflate(decompressed);
            if (!inflater.finished() || length != expectedLength) {
                throw new SaveParseException("Compressed chunk expands to " + length + " bytes instead of " + expectedLength);
            }
            return decompressed;
        } catch (DataFormatException exception) {
            throw new SaveParseException("Invalid compressed save chunk", exception);
        } finally {
            inflater.end();
        }
    }

    private List<SavePartition> readPartitions(BinaryReader reader)
    {
        int partitionCount = this.readCount(reader, "partition count");
        List<SavePartition> partitions = new ArrayList<>(partitionCount);
        for (int index = 0; index < partitionCount; index++) {
            String name = reader.readString();
            long partitionIndex = reader.readUnsignedInt();
            long gridHash = reader.readUnsignedInt();
            int levelCount = this.readCount(reader, "partition level count");
            List<SavePartition.SavePartitionLevel> levels = new ArrayList<>(levelCount);
            for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
                levels.add(new SavePartition.SavePartitionLevel(reader.readString(), reader.readUnsignedInt()));
            }
            partitions.add(new SavePartition(name, partitionIndex, gridHash, levels));
        }
        return partitions;
    }

    private SaveLevel readLevel(BinaryReader reader, long headerSaveVersion, long persistentUnrealEngine5Version, boolean persistent)
    {
        String name = persistent ? null : reader.readString();
        int headerAndCollectableSize = headerSaveVersion == 36
            ? this.readCount(reader, "level header size")
            : this.toLength(reader.readLong(), "level header size", reader);
        int headerStart = reader.offset();
        int objectCount = this.readCount(reader, "object header count");
        List<SaveObjectHeader> headers = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            headers.add(this.readObjectHeader(reader, headerSaveVersion));
        }
        Boolean persistentFlag = null;
        if (persistent) {
            persistentFlag = reader.readBoolean("persistent level flag");
            if (persistentFlag) {
                reader.expectString("Persistent_Level", "persistent level name");
            }
        }
        List<ObjectReference> collectedObjects = new ArrayList<>();
        if (reader.offset() - headerStart < headerAndCollectableSize) {
            this.readReferences(reader, collectedObjects, "collected object");
        }
        if (reader.offset() - headerStart != headerAndCollectableSize) {
            throw reader.error("Level header section size does not match parsed data");
        }

        int allObjectsSize = headerSaveVersion == 36
            ? this.readCount(reader, "level object data size")
            : this.toLength(reader.readLong(), "level object data size", reader);
        byte[] objectBytes = reader.readBytes(allObjectsSize);
        long levelSaveVersion = headerSaveVersion == 36 ? headerSaveVersion : reader.readUnsignedInt();
        List<ObjectReference> destroyedObjects = new ArrayList<>();
        SaveObjectVersionData versionData = null;
        long objectUnrealEngine5Version = persistent ? persistentUnrealEngine5Version : -1;
        if (!persistent) {
            this.readReferences(reader, destroyedObjects, "destroyed object");
            if (headerSaveVersion >= 53 && reader.readBoolean("level version data flag")) {
                versionData = this.readVersionData(reader);
                objectUnrealEngine5Version = versionData.unrealEngine5Version();
            }
        }
        List<SaveObject> objects = new ArrayList<>(objectCount);
        if (headerSaveVersion == 36) {
            for (SaveObjectHeader header : headers) {
                objects.add(new SaveObject(
                    header.instanceName(),
                    0,
                    false,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    new byte[0]
                ));
            }
        } else {
            BinaryReader objectReader = new BinaryReader(objectBytes);
            int serializedObjectCount = this.readCount(objectReader, "serialized object count");
            if (serializedObjectCount != objectCount) {
                throw objectReader.error("Object header count does not match serialized object count");
            }
            for (SaveObjectHeader header : headers) {
                objects.add(this.readObject(objectReader, headerSaveVersion, objectUnrealEngine5Version, header));
            }
            objectReader.requireEnd();
        }
        return new SaveLevel(name, headers, persistentFlag, collectedObjects, objects, levelSaveVersion, destroyedObjects, versionData);
    }

    private SaveObjectHeader readObjectHeader(BinaryReader reader, long headerSaveVersion)
    {
        long type = reader.readUnsignedInt();
        if (type == 1) {
            String typePath = reader.readString();
            String rootObject = reader.readString();
            String instanceName = reader.readString();
            long flags = headerSaveVersion == 36 ? 0 : reader.readUnsignedInt();
            boolean needsTransform = reader.readBoolean("actor transform flag");
            List<Float> rotation = List.of(reader.readFloat(), reader.readFloat(), reader.readFloat(), reader.readFloat());
            List<Float> position = List.of(reader.readFloat(), reader.readFloat(), reader.readFloat());
            List<Float> scale = List.of(reader.readFloat(), reader.readFloat(), reader.readFloat());
            return new ActorHeader(typePath, rootObject, instanceName, flags, needsTransform, rotation, position, scale, reader.readBoolean("actor placed in level flag"));
        }
        if (type == 0) {
            return new ComponentHeader(
                reader.readString(),
                reader.readString(),
                reader.readString(),
                headerSaveVersion == 36 ? 0 : reader.readUnsignedInt(),
                reader.readString()
            );
        }
        throw reader.error("Invalid object header type " + type);
    }

    private SaveObject readObject(BinaryReader reader, long headerSaveVersion, long unrealEngine5Version, SaveObjectHeader header)
    {
        long gameVersion = reader.readUnsignedInt();
        boolean migratesObjectReferences = reader.readBoolean("object migration flag");
        int objectSize = this.readCount(reader, "object size");
        int objectStart = reader.offset();
        SaveObjectVersionData versionData = null;
        long propertyUnrealEngine5Version = unrealEngine5Version;
        ObjectReference parentReference = null;
        List<ObjectReference> componentReferences = new ArrayList<>();
        if (header instanceof ActorHeader) {
            parentReference = this.readReference(reader);
            this.readReferences(reader, componentReferences, "actor component");
        }
        if (propertyUnrealEngine5Version >= 1011) {
            reader.expectUnsignedByte(0, "object serialization control");
        }
        List<SaveProperty> properties = this.readProperties(reader, gameVersion, propertyUnrealEngine5Version);
        reader.expectUnsignedInt(0, "object property terminator");
        int consumed = reader.offset() - objectStart;
        if (consumed > objectSize) {
            throw reader.error("Object content exceeds declared object size");
        }
        byte[] trailingData = reader.readBytes(objectSize - consumed);
        return new SaveObject(header.instanceName(), gameVersion, migratesObjectReferences, versionData, parentReference, componentReferences, properties, trailingData);
    }

    private List<SaveProperty> readProperties(BinaryReader reader, long entityVersion, long unrealEngine5Version)
    {
        List<SaveProperty> properties = new ArrayList<>();
        while (true) {
            String name = reader.readString();
            if ("None".equals(name)) {
                return properties;
            }
            String type = reader.readString();
            boolean modernHeader = unrealEngine5Version >= 1012;
            List<Object> metadata = new ArrayList<>();
            String arrayType = null;
            String structType = null;
            String keyType = null;
            String valueType = null;
            if (modernHeader) {
                long headerType = reader.readUnsignedInt();
                metadata.add(headerType);
                if (headerType == 1 && "ArrayProperty".equals(type)) {
                    arrayType = reader.readString();
                    metadata.add(arrayType);
                    long subtype = reader.readUnsignedInt();
                    metadata.add(subtype);
                    if (subtype == 1) {
                        structType = reader.readString();
                        metadata.add(structType);
                        metadata.add(this.readPackageNames(reader));
                    } else if (subtype == 2) {
                        metadata.add(reader.readString());
                        metadata.add(this.readPackageNames(reader));
                        reader.expectString("ByteProperty", "enum array property type");
                        reader.expectUnsignedInt(0, "enum array property index");
                    }
                } else if (headerType == 1 && ("ByteProperty".equals(type) || "SetProperty".equals(type) || "StructProperty".equals(type))) {
                    String subtype = reader.readString();
                    metadata.add(subtype);
                    metadata.add(this.readPackageNames(reader));
                    if ("SetProperty".equals(type)) {
                        arrayType = subtype;
                    } else if ("StructProperty".equals(type)) {
                        structType = subtype;
                    }
                } else if (headerType == 2 && "EnumProperty".equals(type)) {
                    metadata.add(reader.readString());
                    metadata.add(this.readPackageNames(reader));
                    reader.expectString("ByteProperty", "enum property type");
                    reader.expectUnsignedInt(0, "enum property index");
                } else if (headerType == 2 && "MapProperty".equals(type)) {
                    keyType = reader.readString();
                    metadata.add(keyType);
                    if (reader.readBoolean("map key type name flag")) {
                        reader.expectString("IntVector", "map key type name");
                        metadata.add(this.readPackageNames(reader));
                    }
                    valueType = reader.readString();
                    metadata.add(valueType);
                    if (reader.readBoolean("map value type name flag")) {
                        metadata.add(reader.readString());
                        metadata.add(this.readPackageNames(reader));
                    }
                }
            }
            int size = this.readCount(reader, "property size");
            if (!modernHeader) {
                metadata.add(reader.readUnsignedInt());
            }
            Object value = this.readPropertyValue(reader, type, name, size, modernHeader, metadata, arrayType, structType, keyType, valueType, entityVersion, unrealEngine5Version);
            properties.add(new SaveProperty(name, type, value, metadata));
        }
    }

    private Object readPropertyValue(
        BinaryReader reader,
        String type,
        String name,
        int size,
        boolean modernHeader,
        List<Object> metadata,
        String arrayType,
        String structType,
        String keyType,
        String valueType,
        long entityVersion,
        long unrealEngine5Version
    )
    {
        if ("BoolProperty".equals(type)) {
            int value = reader.readUnsignedByte();
            if (!modernHeader) {
                reader.expectUnsignedByte(0, "boolean property guid flag");
            }
            return value;
        }
        if (!modernHeader) {
            if ("ByteProperty".equals(type) || "EnumProperty".equals(type)) {
                metadata.add(reader.readString());
            } else if ("ArrayProperty".equals(type) || "SetProperty".equals(type)) {
                arrayType = reader.readString();
                metadata.add(arrayType);
            } else if ("StructProperty".equals(type)) {
                structType = reader.readString();
                metadata.add(structType);
                metadata.add(reader.readLong());
                metadata.add(reader.readLong());
            } else if ("MapProperty".equals(type)) {
                keyType = reader.readString();
                valueType = reader.readString();
                metadata.add(keyType);
                metadata.add(valueType);
            }
        }
        reader.expectUnsignedByte(0, "property guid flag");
        int valueStart = reader.offset();
        Object value = switch (type) {
            case "ByteProperty" -> this.readByteProperty(reader, metadata);
            case "Int8Property" -> reader.readByte();
            case "IntProperty" -> reader.readInt();
            case "UInt32Property" -> reader.readUnsignedInt();
            case "Int64Property" -> reader.readLong();
            case "FloatProperty" -> reader.readFloat();
            case "DoubleProperty" -> reader.readDouble();
            case "EnumProperty", "StrProperty", "NameProperty" -> reader.readString();
            case "ObjectProperty" -> this.readReference(reader);
            case "SoftObjectProperty" -> List.of(this.readReference(reader), reader.readUnsignedInt());
            case "ArrayProperty" -> this.readArray(reader, arrayType, structType, entityVersion, unrealEngine5Version);
            case "SetProperty" -> this.readSet(reader, arrayType);
            case "MapProperty" -> this.readMap(reader, keyType, valueType, entityVersion, unrealEngine5Version);
            case "StructProperty" -> this.readStruct(reader, structType, size, entityVersion, unrealEngine5Version);
            default -> reader.readBytes(size);
        };
        if (reader.offset() - valueStart != size) {
            throw reader.error("Property " + name + " of type " + type + " does not consume its declared size");
        }
        return value;
    }

    private Object readByteProperty(BinaryReader reader, List<Object> metadata)
    {
        String enumName = metadata.isEmpty() ? null : String.valueOf(metadata.getLast());
        return enumName == null || "None".equals(enumName) ? reader.readUnsignedByte() : reader.readString();
    }

    private List<Object> readArray(BinaryReader reader, String arrayType, String structType, long entityVersion, long unrealEngine5Version)
    {
        reader.readUnsignedByte();
        int count = this.readCount(reader, "array element count");
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(this.readCollectionValue(reader, arrayType, structType, entityVersion, unrealEngine5Version));
        }
        return values;
    }

    private List<Object> readSet(BinaryReader reader, String elementType)
    {
        reader.readUnsignedByte();
        reader.expectUnsignedInt(0, "set mode");
        int count = this.readCount(reader, "set element count");
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(switch (elementType) {
                case "UInt32Property" -> reader.readUnsignedInt();
                case "ObjectProperty" -> this.readReference(reader);
                case "StructProperty" -> List.of(reader.readLong(), reader.readLong());
                default -> throw reader.error("Unsupported set element type " + elementType);
            });
        }
        return values;
    }

    private Map<Object, Object> readMap(BinaryReader reader, String keyType, String valueType, long entityVersion, long unrealEngine5Version)
    {
        reader.readUnsignedByte();
        reader.expectUnsignedInt(0, "map mode");
        int count = this.readCount(reader, "map entry count");
        Map<Object, Object> values = new LinkedHashMap<>(count);
        for (int index = 0; index < count; index++) {
            Object key = this.readCollectionValue(reader, keyType, null, entityVersion, unrealEngine5Version);
            Object value = this.readCollectionValue(reader, valueType, null, entityVersion, unrealEngine5Version);
            values.put(key, value);
        }
        return values;
    }

    private Object readStruct(BinaryReader reader, String structType, int size, long entityVersion, long unrealEngine5Version)
    {
        return switch (structType) {
            case "LinearColor" -> List.of(reader.readFloat(), reader.readFloat(), reader.readFloat(), reader.readFloat());
            case "Vector2D" -> List.of(reader.readDouble(), reader.readDouble());
            case "Vector" -> List.of(reader.readDouble(), reader.readDouble(), reader.readDouble());
            case "Quat" -> List.of(reader.readDouble(), reader.readDouble(), reader.readDouble(), reader.readDouble());
            case "DateTime" -> reader.readLong();
            case "InventoryItem" -> this.readInventoryItem(reader, entityVersion, unrealEngine5Version);
            default -> reader.readBytes(size);
        };
    }

    private Object readInventoryItem(BinaryReader reader, long entityVersion, long unrealEngine5Version)
    {
        reader.expectUnsignedInt(0, "inventory item level");
        String itemName = reader.readString();
        if (!reader.readBoolean("inventory item property flag")) {
            return List.of(itemName, List.of());
        }
        reader.expectUnsignedInt(0, "inventory item property level");
        String typePath = reader.readString();
        int size = this.readCount(reader, "inventory item property size");
        int start = reader.offset();
        List<SaveProperty> properties = this.readProperties(reader, entityVersion, unrealEngine5Version);
        if (reader.offset() - start != size) {
            throw reader.error("Inventory item property size does not match parsed data");
        }
        return List.of(itemName, typePath, properties);
    }

    private Object readCollectionValue(BinaryReader reader, String type, String structType, long entityVersion, long unrealEngine5Version)
    {
        return switch (type) {
            case "IntProperty" -> reader.readInt();
            case "Int64Property" -> reader.readLong();
            case "UInt32Property" -> reader.readUnsignedInt();
            case "ByteProperty" -> reader.readUnsignedByte();
            case "FloatProperty" -> reader.readFloat();
            case "DoubleProperty" -> reader.readDouble();
            case "StrProperty", "NameProperty", "EnumProperty" -> reader.readString();
            case "ObjectProperty", "InterfaceProperty" -> this.readReference(reader);
            case "SoftObjectProperty" -> List.of(this.readReference(reader), reader.readUnsignedInt());
            case "StructProperty" -> structType == null ? this.readProperties(reader, entityVersion, unrealEngine5Version) : this.readStruct(reader, structType, Integer.MAX_VALUE, entityVersion, unrealEngine5Version);
            default -> throw reader.error("Unsupported collection value type " + type);
        };
    }

    private List<String> readPackageNames(BinaryReader reader)
    {
        List<String> names = new ArrayList<>();
        if (reader.readBoolean("package name flag")) {
            names.add(reader.readString());
            if (reader.readBoolean("additional package name flag")) {
                names.add(reader.readString());
                names.add(reader.readString());
            }
        }
        return names;
    }

    private SaveObjectVersionData readVersionData(BinaryReader reader)
    {
        long version = reader.readUnsignedInt();
        long unrealEngine4Version = reader.readUnsignedInt();
        long unrealEngine5Version = reader.readUnsignedInt();
        long licenseeVersion = reader.readUnsignedInt();
        SaveObjectVersionData.EngineVersion engineVersion = new SaveObjectVersionData.EngineVersion(
            reader.readUnsignedShort(),
            reader.readUnsignedShort(),
            reader.readUnsignedShort(),
            reader.readUnsignedInt(),
            reader.readString()
        );
        int customVersionCount = this.readCount(reader, "custom version count");
        List<SaveObjectVersionData.CustomVersion> customVersions = new ArrayList<>(customVersionCount);
        for (int index = 0; index < customVersionCount; index++) {
            customVersions.add(new SaveObjectVersionData.CustomVersion(reader.readLong(), reader.readLong(), reader.readUnsignedInt()));
        }
        return new SaveObjectVersionData(version, unrealEngine4Version, unrealEngine5Version, licenseeVersion, engineVersion, customVersions);
    }

    private ObjectReference readReference(BinaryReader reader)
    {
        return new ObjectReference(reader.readString(), reader.readString());
    }

    private void readReferences(BinaryReader reader, List<ObjectReference> references, String context)
    {
        int count = this.readCount(reader, context + " count");
        for (int index = 0; index < count; index++) {
            references.add(this.readReference(reader));
        }
    }

    private int readCount(BinaryReader reader, String context)
    {
        return this.toLength(reader.readUnsignedInt(), context, reader);
    }

    private int toLength(long value, String context, BinaryReader reader)
    {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw reader.error("Unsupported " + context + " " + Long.toUnsignedString(value));
        }
        return (int) value;
    }
}
