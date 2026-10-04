package com.storyforge.log;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    List<Attempt> findByStoryIdOrderByIdAsc(Long storyId);
}
