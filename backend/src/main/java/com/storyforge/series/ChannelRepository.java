package com.storyforge.series;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    /** The channel new topics start on (one row, enforced by a unique index; created by V15). */
    @Query("select c from Channel c where c.isDefault = true")
    Channel findDefault();
}
