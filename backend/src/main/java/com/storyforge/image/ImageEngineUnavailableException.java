package com.storyforge.image;

/** The image engine could not produce an image for reasons that aren't the prompt's fault. Never a strike. */
public class ImageEngineUnavailableException extends RuntimeException {

    public ImageEngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
