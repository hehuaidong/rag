package com.example.rag.repository;

import com.example.rag.entity.ChatHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    Page<ChatHistory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    void deleteByUserId(String userId);

    List<ChatHistory> findTop10ByUserIdOrderByCreatedAtDesc(String userId);
}
