package com.inshot.hx.myhelper.entity.converter;

import com.inshot.hx.myhelper.entity.enums.MessageRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 保持文档约定的数据库值 user/assistant/system/tool，而不是枚举名称。 */
@Converter
public class MessageRoleConverter implements AttributeConverter<MessageRole, String> {

    @Override
    public String convertToDatabaseColumn(MessageRole role) {
        return role == null ? null : role.getValue();
    }

    @Override
    public MessageRole convertToEntityAttribute(String value) {
        return value == null ? null : MessageRole.fromValue(value);
    }
}
