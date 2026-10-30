package com.storyforge.story;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StoryFormatConverter implements AttributeConverter<StoryFormat, String> {

    @Override
    public String convertToDatabaseColumn(StoryFormat format) {
        return format == null ? null : format.dbValue();
    }

    @Override
    public StoryFormat convertToEntityAttribute(String value) {
        return value == null ? null : StoryFormat.fromDb(value);
    }
}
