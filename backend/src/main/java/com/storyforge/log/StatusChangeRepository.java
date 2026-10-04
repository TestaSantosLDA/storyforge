package com.storyforge.log;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusChangeRepository extends JpaRepository<StatusChange, Long> {

    List<StatusChange> findByStoryIdOrderByIdAsc(Long storyId);

    void deleteByStoryId(Long storyId);
}
