package com.storyforge.story;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StoryStatusConverter implements AttributeConverter<StoryStatus, String> {

    @Override
    public String convertToDatabaseColumn(StoryStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public StoryStatus convertToEntityAttribute(String value) {
        return value == null ? null : StoryStatus.fromDb(value);
    }
}
