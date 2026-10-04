package com.storyforge.topic;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    List<Topic> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
