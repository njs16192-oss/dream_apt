package com.dreamapt.notification;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<NotificationItem> findPage(long memberId, int page, int size) {
        long total = repository.count(memberId);
        List<NotificationItem> content = repository.findPage(memberId, size, (long) page * size);
        return PageResponse.of(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public long countUnread(long memberId) {
        return repository.countUnread(memberId);
    }

    /** 남의 알림도 없는 알림과 같은 404다. 다른 회원에게 그 id의 알림이 있는지 드러내지 않는다 */
    @Transactional
    public void markRead(long memberId, long id) {
        if (!repository.markRead(memberId, id)) {
            throw new NotFoundException("알림을 찾을 수 없습니다");
        }
    }

    @Transactional
    public void markAllRead(long memberId) {
        repository.markAllRead(memberId);
    }
}
