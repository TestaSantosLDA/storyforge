package com.storyforge.topic;

/** Topic deletion refused (docs/pipeline-a/stage-0 scenario 6). */
public class TopicInUseException extends RuntimeException {

    public TopicInUseException(String message) {
        super(message);
    }
}
