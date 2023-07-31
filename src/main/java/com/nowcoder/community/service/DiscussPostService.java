package com.nowcoder.community.service;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nowcoder.community.mapper.DiscussPostMapper;
import com.nowcoder.community.pojo.DiscussPost;
import com.nowcoder.community.util.RedisKeyUtil;
import com.nowcoder.community.util.SensitiveFilter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DiscussPostService {

    @Resource
    private DiscussPostMapper discussPostMapper;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private SensitiveFilter sensitiveFilter;

    @Value("${caffeine.posts.max-size}")
    private int maxSize;

    @Value("${caffeine.posts.expire-seconds}")
    private int expireSecs;

    //核心接口：Cache，子接口loadingCache 同步缓存，AsyLoadingCache 异步缓存
    //帖子列表缓存
    private LoadingCache<String, List<DiscussPost>> postListCache;

    //帖子总数缓存
    private LoadingCache<Integer, Integer> postRowsCache;

    @PostConstruct
    public void init() {
        //初始化帖子列表缓存
        postListCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSecs, TimeUnit.SECONDS)
                .build(new CacheLoader<String, List<DiscussPost>>() {
                    @Override
                    public List<DiscussPost> load(String key) throws Exception {
                        if (StringUtils.isBlank(key)) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        String[] params = key.split(":");
                        if (params == null || params.length != 2) {
                            throw new IllegalArgumentException("参数错误");
                        }
                        int offset = Integer.valueOf(params[0]);
                        int limit = Integer.valueOf(params[1]);
                        //二级缓存：redis->mysql
                        log.debug("load post list from DB.");
                        return discussPostMapper.selectDiscussPosts(0, offset, limit, 1);
                    }
                });
        //初始化帖子总数缓存
        postRowsCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSecs, TimeUnit.SECONDS)
                .build(new CacheLoader<Integer, Integer>() {
                    @Override
                    public Integer load(Integer key) throws Exception {
                        log.debug("load post list from DB.");
                        return discussPostMapper.selectDiscussPostRows(key);
                    }
                });
    }

    public List<DiscussPost> findDiscussPosts(int userId, int offset, int limit, int orderMode) {
        if (userId == 0 && orderMode == 1) {
            //将热门贴子缓存至本地
            return postListCache.get(offset + ":" + limit);
        }
        log.debug("load post list from DB.");
        return discussPostMapper.selectDiscussPosts(userId, offset, limit, orderMode);
    }

    public int findDiscussPostRows(int userId) {
        if (userId == 0) {
            return postRowsCache.get(userId);
        }
        return discussPostMapper.selectDiscussPostRows(userId);
    }

    public int addDiscussPost(DiscussPost post) {
        if (post == null) {
            throw new IllegalArgumentException("参数不能为空!");
        }

        // 转义HTML标记
        post.setTitle(HtmlUtils.htmlEscape(post.getTitle()));
        post.setContent(HtmlUtils.htmlEscape(post.getContent()));
        // 过滤敏感词
        post.setTitle(sensitiveFilter.filter(post.getTitle()));
        post.setContent(sensitiveFilter.filter(post.getContent()));
        int rows = discussPostMapper.insertDiscussPost(post);
        if (rows>0) {
            //增加redis缓存
            String key = RedisKeyUtil.getCachePostKey(post.getId());
            Long expireMinutes = (long) (Math.random() * 20 + 20);
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(post), expireMinutes, TimeUnit.MINUTES);
        }
        return rows;
    }

    public DiscussPost findDiscussPostById(int id) {
        //将帖子存入Redis缓存，并使用空值处理和随机过期时间解决缓存穿透和缓存雪崩问题
        //获取缓存post key
        String key= RedisKeyUtil.getCachePostKey(id);
        //1、从Redis中查询帖子缓存
        String postJson= (String) redisTemplate.opsForValue().get(key);
        //2、判断缓存是否存在
        if ((StringUtils.isNotBlank(postJson))) {//isBlank 包括空值"" 和null
            //3、存在，直接返回
            DiscussPost cacheShop= JSONUtil.toBean(postJson, DiscussPost.class);
            return cacheShop;
        }
        //  判断命中的是否是空值
        if (postJson != null) {
            // 返回null
            return null;
        }
        //4、不存在，根据id查询数据库
        log.debug("load post  from DB.");
        DiscussPost post = discussPostMapper.selectDiscussPostById(id);
        //5、数据库查询不存在，返回错误
        //过期时间
        Long expireMinutes= (long) (Math.random()*20+20);
        if (post == null) {
            //为了解决缓存穿透，将空值存入redis
            redisTemplate.opsForValue().set(key, "",expireMinutes , TimeUnit.MINUTES);
        }
        //6、数据库查询存在，写入Redis,时效20-40分钟
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(post), expireMinutes, TimeUnit.MINUTES);
        //7、返回
        return post;
    }

    public int updateCommentCount(int id, int commentCount) {
        //删除Redis缓存
        String key= RedisKeyUtil.getCachePostKey(id);
        redisTemplate.delete(key);
        return discussPostMapper.updateCommentCount(id, commentCount);
    }

    public int updateType(int id, int type) {
        //删除Redis缓存
        String key= RedisKeyUtil.getCachePostKey(id);
        redisTemplate.delete(key);
        return discussPostMapper.updateType(id, type);
    }

    public int updateStatus(int id, int status) {
        //删除Redis缓存
        String key= RedisKeyUtil.getCachePostKey(id);
        redisTemplate.delete(key);
        return discussPostMapper.updateStatus(id, status);
    }

    public int updateScore(int id, double score) {
        //删除Redis缓存
        String key= RedisKeyUtil.getCachePostKey(id);
        redisTemplate.delete(key);
        return discussPostMapper.updateScore(id, score);
    }

}
