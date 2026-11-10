package com.dreamapt.watchlist;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dreamapt.apt.AptComplexRepository;
import com.dreamapt.common.ConflictException;
import com.dreamapt.common.NotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    static final int MAX_WATCHLIST = 50;

    private final WatchlistRepository watchlistRepository;
    private final AptComplexRepository aptComplexRepository;

    @Transactional
    public void add(long memberId, String aptSeq) {
        //이미 내 목록에 있으면 아무것도 안 하고 끝 (return)
        
        if(!aptComplexRepository.existsById(aptSeq)){
            throw new NotFoundException("단지를 찾을 수 없습니다");
        }

        if(watchlistRepository.exists(memberId, aptSeq)) return;

        if(watchlistRepository.count(memberId) >= MAX_WATCHLIST) throw new ConflictException ("관심단지는 최대 50개까지입니다");
        
        watchlistRepository.add(memberId, aptSeq);
        
    }

    @Transactional
    public void remove(long memberId, String aptSeq) {
        watchlistRepository.remove(memberId, aptSeq);
    }
}