package com.storyforge.story;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StoryRepository extends JpaRepository<Story, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Story s where s.id = :id")
    Optional<Story> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Story s where s.status = com.storyforge.story.StoryStatus.QUEUED order by s.queuePosition")
    List<Story> findQueuedForUpdate(Limit limit);

    long countByStatusIn(Collection<StoryStatus> statuses);

    List<Story> findByStatusIn(Collection<StoryStatus> statuses);

    @Query("select coalesce(max(s.queuePosition), 0) from Story s")
    int maxQueuePosition();
}
