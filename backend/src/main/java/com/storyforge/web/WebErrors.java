package com.storyforge.web;

import com.storyforge.story.StatusConflictException;
import com.storyforge.topic.TopicInUseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Refusals (a started story, a topic in use) go back to the previous page with the reason, or as 409 JSON for the
 * drag-to-reorder call. The backend refuses whatever the page showed (Stage 0 scenarios 4 and 6).
 */
@ControllerAdvice
class WebErrors {

    @ExceptionHandler({StatusConflictException.class, TopicInUseException.class})
    Object refused(RuntimeException e, HttpServletRequest req) {
        if (req.getRequestURI().startsWith("/queue/reorder")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        RequestContextUtils.getOutputFlashMap(req).put("error", e.getMessage());
        String referer = req.getHeader("Referer");
        String back = referer == null ? "/queue" : StoryController.safeBack(java.net.URI.create(referer).getPath());
        return new ModelAndView("redirect:" + back);
    }

    @ExceptionHandler(NotFoundException.class)
    ModelAndView notFound(NotFoundException e) {
        ModelAndView mv = new ModelAndView("not-found", Map.of("what", e.getMessage()));
        mv.setStatus(HttpStatus.NOT_FOUND);
        return mv;
    }
}
