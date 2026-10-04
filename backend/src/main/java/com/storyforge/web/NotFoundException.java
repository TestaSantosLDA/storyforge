package com.storyforge.web;

class NotFoundException extends RuntimeException {

    NotFoundException(String what) {
        super(what);
    }
}
