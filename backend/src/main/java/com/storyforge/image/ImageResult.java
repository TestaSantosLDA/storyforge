package com.storyforge.image;

public record ImageResult(byte[] png, String engineVersion, double seconds, Double peakVramGb) {
}
