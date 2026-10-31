package com.storyforge.series;

import com.storyforge.log.ChangeLog;
import com.storyforge.validation.InvalidInputException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Channels on the management page (C2). Edits apply to stories started afterwards; running stories keep theirs. */
@Service
public class ChannelService {

    private final ChannelRepository channels;
    private final ChangeLog changeLog;

    ChannelService(ChannelRepository channels, ChangeLog changeLog) {
        this.channels = channels;
        this.changeLog = changeLog;
    }

    @Transactional(readOnly = true)
    public List<Channel> all() {
        return channels.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Channel byDefault() {
        return channels.findDefault();
    }

    @Transactional
    public Channel create(ChannelForm input, String by) {
        ChannelForm f = input.validated();
        if (channels.existsByNameIgnoreCase(f.name())) {
            throw InvalidInputException.of("name", "A channel with this name already exists.");
        }
        Channel c = new Channel(f.name());
        c.edit(f);
        c = channels.save(c);
        changeLog.record("channel", c.getId(), "create", null, c.details(), by);
        return c;
    }

    @Transactional
    public Channel edit(long id, ChannelForm input, String by) {
        ChannelForm f = input.validated();
        Channel c = channels.findById(id).orElseThrow();
        if (!c.getName().equalsIgnoreCase(f.name()) && channels.existsByNameIgnoreCase(f.name())) {
            throw InvalidInputException.of("name", "A channel with this name already exists.");
        }
        var before = c.details();
        c.edit(f);
        changeLog.record("channel", id, "edit", before, c.details(), by);
        return c;
    }
}
