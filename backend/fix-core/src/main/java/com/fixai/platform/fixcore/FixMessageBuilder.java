package com.fixai.platform.fixcore;

import java.util.List;
import java.util.Map;
import quickfix.DataDictionary;
import quickfix.FieldMap;
import quickfix.Group;
import quickfix.Message;
import quickfix.field.ApplVerID;
import quickfix.field.MsgType;

/**
 * Builds QuickFIX/J messages from declarative field maps, resolving dictionary field names to tags.
 *
 * <p>Values are {@link String}s for plain fields, or a {@code List<Map<String, Object>>} for a repeating group keyed by
 * its NoXxx count field (for example {@code NoPartyIDs}). Unknown names are rejected so that scenario typos fail
 * loudly instead of producing silently different messages.
 */
public final class FixMessageBuilder {

    private final FixVersion version;
    private final DataDictionary transport;
    private final DataDictionary application;

    public FixMessageBuilder(FixVersion version, DictionaryRegistry dictionaries) {
        this.version = version;
        this.transport = dictionaries.transport(version);
        this.application = dictionaries.application(version);
    }

    public FixMessageBuilder(FixVersion version) {
        this(version, DictionaryRegistry.shared());
    }

    public Message build(String msgType, Map<String, Object> fields) {
        DataDictionary bodyDictionary = isAdmin(msgType) ? transport : application;
        if (!bodyDictionary.isMsgType(msgType)) {
            throw new IllegalArgumentException("MsgType " + msgType + " is not defined for " + version.beginString());
        }
        Message message = new Message();
        message.getHeader().setString(MsgType.FIELD, msgType);
        if (version.isFixt() && !isAdmin(msgType)) {
            message.getHeader().setString(ApplVerID.FIELD, version.defaultApplVerId().orElseThrow());
        }
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            int tag = resolveTag(entry.getKey());
            if (transport.isHeaderField(tag)) {
                message.getHeader().setString(tag, String.valueOf(entry.getValue()));
            } else {
                setField(message, msgType, tag, entry.getValue(), bodyDictionary);
            }
        }
        return message;
    }

    /** Resolves a dictionary field name or numeric tag text to a tag number. */
    public int resolveTag(String nameOrTag) {
        if (!nameOrTag.isEmpty() && nameOrTag.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(nameOrTag);
        }
        int tag = application.getFieldTag(nameOrTag);
        if (tag < 0) {
            tag = transport.getFieldTag(nameOrTag);
        }
        if (tag < 0) {
            throw new IllegalArgumentException("Unknown FIX field name '" + nameOrTag + "' for " + version.beginString());
        }
        return tag;
    }

    @SuppressWarnings("unchecked")
    private void setField(FieldMap target, String msgType, int tag, Object value, DataDictionary dictionary) {
        if (value instanceof List<?> entries) {
            DataDictionary.GroupInfo info = dictionary.getGroup(msgType, tag);
            if (info == null) {
                throw new IllegalArgumentException("Tag " + tag + " is not a repeating group in " + msgType);
            }
            DataDictionary groupDictionary = info.getDataDictionary();
            for (Object entry : entries) {
                Group group = new Group(tag, info.getDelimiterField(), groupDictionary.getOrderedFields());
                for (Map.Entry<String, Object> member : ((Map<String, Object>) entry).entrySet()) {
                    setField(group, msgType, resolveTag(member.getKey()), member.getValue(), groupDictionary);
                }
                target.addGroup(group);
            }
        } else {
            target.setString(tag, String.valueOf(value));
        }
    }

    private static boolean isAdmin(String msgType) {
        return quickfix.MessageUtils.isAdminMessage(msgType);
    }
}
