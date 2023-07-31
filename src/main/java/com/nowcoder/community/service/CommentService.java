package com.nowcoder.community.service;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nowcoder.community.mapper.CommentMapper;
import com.nowcoder.community.pojo.Comment;
import com.nowcoder.community.util.CommunityConstant;
import com.nowcoder.community.util.RedisKeyUtil;
import com.nowcoder.community.util.SensitiveFilter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CommentService implements CommunityConstant {

    @Resource
    private CommentMapper commentMapper;

    @Resource
    private SensitiveFilter sensitiveFilter;

    @Resource
    private DiscussPostService discussPostService;

    @Resource
    private RedisTemplate redisTemplate;

    @Value("${caffeine.comments.max-size}")
    private int maxSize;

    @Value("${caffeine.comments.expire-seconds}")
    private int expireSecs;
    //核心接口：Cache，子接口loadingCache 同步缓存，AsyLoadingCache 异步缓存
    //帖子列表缓存
    private LoadingCache<String, List<Comment>> commentListCache;

    //帖子总数缓存
    private LoadingCache<String, Integer> commentRowsCache;

    @PostConstruct
    public void init() {
        //初始化评论列表缓存
        commentListCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSecs, TimeUnit.SECONDS)
                .build(new CacheLoader<String, List<Comment>>() {
                    @Override
                    public List<Comment> load(String key) throws Exception {
                        if (StringUtils.isBlank(key)) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        String[] params = key.split(":");
                        if (params == null || params.length != 4) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        int entityType = Integer.valueOf(params[0]);
                        int entityId = Integer.valueOf(params[1]);
                        int offset = Integer.valueOf(params[2]);
                        int limit = Integer.valueOf(params[3]);
                        log.debug("load post list from DB.");
                        return commentMapper.selectCommentsByEntity(entityType, entityId, offset, limit);
                    }
                });
        //初始化评论总数缓存
        commentRowsCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSecs, TimeUnit.SECONDS)
                .build(new CacheLoader<String, Integer>() {
                    @Override
                    public Integer load(String key) throws Exception {
                        if (StringUtils.isBlank(key)) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        String[] params = key.split(":");
                        if (params == null || params.length != 2) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        int entityType = Integer.valueOf(params[0]);
                        int entityId = Integer.valueOf(params[1]);
                        log.debug("load post list from DB.");
                        return commentMapper.selectCountByEntity(entityType, entityId);
                    }
                });
    }

    public List<Comment> findCommentsByEntity(int entityType, int entityId, int offset, int limit) {
        log.debug("load comment list from DB.");
        return commentListCache.get(entityType + ":" + entityId + ":" + offset + ":" + limit);
    }

    public int findCommentCount(int entityType, int entityId) {
        log.debug("load comment rows from DB.");
        return commentRowsCache.get(entityType + ":" + entityId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public int addComment(Comment comment) {
        if (comment == null) {
            throw new IllegalArgumentException("参数不能为空!");
        }
        // 添加评论
        comment.setContent(HtmlUtils.htmlEscape(comment.getContent()));
        comment.setContent(sensitiveFilter.filter(comment.getContent()));
        int rows = commentMapper.insertComment(comment);
        // 更新帖子评论数量
        if (comment.getEntityType() == ENTITY_TYPE_POST) {
            int count = commentMapper.selectCountByEntity(comment.getEntityType(), comment.getEntityId());
            discussPostService.updateCommentCount(comment.getEntityId(), count);
        }
        //将评论增加Redis缓存
        if (rows>0) {
            //增加redis缓存
            String key = RedisKeyUtil.getCacheCommentKey(comment.getId());
            Long expireMinutes = (long) (Math.random() * 20 + 20);
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(comment), expireMinutes, TimeUnit.SECONDS);
        }
        return rows;
    }

    public Comment findCommentById(int id) {
        String key = RedisKeyUtil.getCacheCommentKey(id);
        //从Redis查询缓存
        String commentJson = (String) redisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if (StringUtils.isNotBlank(key)){
            //缓存存在，直接返回
            Comment cacheComment = JSONUtil.toBean(commentJson, Comment.class);
            return cacheComment;
        }
        //  判断命中的是否是空值
        if (commentJson != null) {
            // 返回null
            return null;
        }
        //4、不存在，根据id查询数据库
        log.debug("load post list from DB.");
        Comment comment = commentMapper.selectCommentById(id);
        //5、数据库查询不存在，返回错误
        //过期时间
        Long expireMinutes= (long) (Math.random()*20+20);
        if (comment == null) {
            //为了解决缓存穿透，将空值存入redis
            redisTemplate.opsForValue().set(key, "",expireMinutes , TimeUnit.SECONDS);
        }
        //6、数据库查询存在，写入Redis,时效20-40分钟
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(comment), expireMinutes, TimeUnit.SECONDS);
        //7、返回
        return comment;
    }
}
