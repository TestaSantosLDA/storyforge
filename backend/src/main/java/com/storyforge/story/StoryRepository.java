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

    @Query("select s from Story s join fetch s.topic where s.status = com.storyforge.story.StoryStatus.QUEUED "
            + "order by s.queuePosition")
    List<Story> findQueuedInOrder();

    @Query("select s from Story s join fetch s.topic where s.status in :statuses order by s.updatedAt desc")
    List<Story> findForView(Collection<StoryStatus> statuses);

    @Query("select s from Story s join fetch s.topic where s.id = :id")
    Optional<Story> findWithTopic(long id);

    @Query("select s from Story s where s.topic.id = :topicId order by s.createdAt")
    List<Story> findByTopicId(long topicId);

    /** True if any of the topic's stories ever left the queue (restored stories included). */
    @Query(value = """
            select exists (select 1 from status_change c join story s on s.id = c.story_id
                           where s.topic_id = :topicId and c.from_status = 'queued')
            """, nativeQuery = true)
    boolean anyStartedInTopic(long topicId);

    long countByStatusIn(Collection<StoryStatus> statuses);

    List<Story> findByStatusIn(Collection<StoryStatus> statuses);

    @Query("select coalesce(max(s.queuePosition), 0) from Story s")
    int maxQueuePosition();
}
