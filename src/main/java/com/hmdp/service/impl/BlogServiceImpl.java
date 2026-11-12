package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.repository.BlogRepository;
import com.hmdp.repository.ShopRepository;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IMediaService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BlogServiceImpl implements IBlogService {
  @Resource
  private BlogRepository blogRepository;

  private Blog getById(Long id) {
    return id == null ? null : blogRepository.findById(id).orElse(null);
  }

  private static PageRequest pageOf(Integer current, int size, Sort sort) {
    return PageRequest.of(Math.max(1, current == null ? 1 : current) - 1, size, sort);
  }

  /** Returns rows in the order of {@code ids}; replaces SQL {@code ORDER BY FIELD(...)}. */
  private static <T> List<T> inIdOrder(List<Long> ids, List<T> rows, Function<T, Long> idOf) {
    Map<Long, T> byId = new HashMap<>();
    for (T row : rows) {
      byId.put(idOf.apply(row), row);
    }
    List<T> ordered = new ArrayList<>(ids.size());
    for (Long id : ids) {
      T row = byId.get(id);
      if (row != null) {
        ordered.add(row);
      }
    }
    return ordered;
  }

  @Resource
  private ShopRepository shopRepository;

  @Resource
  private IUserService userService;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private IFollowService followService;

  @Resource
  private TransactionTemplate transactions;

  @Resource
  private IBusinessOutboxService outbox;

  @Resource
  private IMediaService media;

  @Override
  public Result queryHotBlog(Integer current) {
    // Query by user
    Page<Blog> page =
        blogRepository.findAll(
            pageOf(
                current,
                SystemConstants.MAX_PAGE_SIZE,
                Sort.by(Sort.Order.desc("liked"), Sort.Order.desc("id"))));
    // Get the current page of data
    List<Blog> records = page.getContent();
    enrich(records);
    return Result.ok(records);
  }

  @Override
  public Result queryBlogById(Long id) {
    // 1. Query the blog
    Blog blog = getById(id);
    if (blog == null) {
      return Result.fail("Post not found.");
    }
    // 2. Query the blog's author
    queryBlogUser(blog);
    // 3. Check whether the blog is liked
    isBlogLiked(blog);
    return Result.ok(blog);
  }

  private void isBlogLiked(Blog blog) {
    // 1. Get the logged-in user
    UserDTO user = UserHolder.getUser();
    if (user == null) {
      // Not logged in, so no need to check likes
      return;
    }
    blog.setIsLike(blogRepository.countLike(blog.getId(), user.getId()) > 0);
  }

  @Override
  public Result likeBlog(Long id) {
    return updateLike(id, null);
  }

  @Override
  public Result setBlogLike(Long id, boolean liked) {
    return updateLike(id, liked);
  }

  private Result updateLike(Long id, Boolean requested) {
    long user = UserHolder.getUser().getId();
    return transactions.execute(
        tx -> {
          if (blogRepository.selectIdForUpdate(id).isEmpty()) {
            return Result.fail("Post not found.");
          }
          boolean current = blogRepository.countLike(id, user) > 0;
          boolean desired = requested == null ? !current : requested;
          if (current == desired) {
            return Result.ok();
          }
          if (desired) {
            blogRepository.insertLike(id, user);
            blogRepository.incrementLikes(id);
          } else {
            blogRepository.deleteLike(id, user);
            blogRepository.decrementLikes(id);
          }
          outbox.appendBusinessEvent("LIKE", id, user);
          return Result.ok();
        });
  }

  @Override
  public Result queryBlogLikes(Long id) {
    List<Long> ids = blogRepository.selectFirstLikeUserIds(id);
    if (ids.isEmpty()) {
      return Result.ok(Collections.emptyList());
    }
    // 3. Query users by ID, preserving the order of likes
    List<UserDTO> userDTOS =
        inIdOrder(ids, userService.listByIds(ids), User::getId).stream()
            .map(UserDTO::from)
            .collect(Collectors.toList());
    // 4. Return
    return Result.ok(userDTOS);
  }

  @Override
  @Transactional
  public Result saveBlog(Blog blog) {
    // 1. Get the logged-in user
    UserDTO user = UserHolder.getUser();
    blog.setUserId(user.getId());
    if (blog.getImages() == null) {
      blog.setImages("");
    }
    if (blog.getImages().length() > 2048
        || (!blog.getImages().isEmpty() && blog.getImages().split(",").length > 9)) {
      return Result.fail("Invalid image count or path length.");
    }
    if (!media.lockOwnedImages(blog.getImages(), user.getId())) {
      return Result.fail("Image not found, not yours, or being deleted.");
    }
    blog.setId(null);
    blog.setLiked(0);
    blog.setComments(0);
    blog.setCreateTime(null);
    blog.setUpdateTime(null);
    if (blog.getTitle() == null
        || blog.getTitle().trim().isEmpty()
        || blog.getTitle().length() > 255
        || blog.getContent() == null
        || blog.getContent().length() > 10000
        || blog.getShopId() == null
        || shopRepository.countByShopId(blog.getShopId()) == 0) {
      return Result.fail("Invalid post content or shop.");
    }
    // 2. Save the shop post
    blogRepository.insert(blog);
    media.attach(blog.getId(), blog.getImages());
    outbox.appendBusinessEvent("FEED", blog.getId(), user.getId());
    return Result.ok(blog.getId());
  }

  @Override
  public Result queryBlogOfFollow(Long max, Integer offset) {
    // 1. Get the current user
    Long userId = UserHolder.getUser().getId();
    // 2. Query the inbox: ZREVRANGEBYSCORE key Max Min LIMIT offset count
    String key = RedisConstants.FEED_KEY + userId;
    Set<ZSetOperations.TypedTuple<String>> typedTuples =
        stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    // 3. Check for an empty result
    if (typedTuples == null || typedTuples.isEmpty()) {
      return Result.ok();
    }
    // 4. Parse the data: blogId, minTime (timestamp), offset
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0; // 2
    int os = 1; // 2
    for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
      // 4.1. Get the ID
      ids.add(Long.valueOf(tuple.getValue()));
      // 4.2. Get the score (timestamp)
      long time = tuple.getScore().longValue();
      if (time == minTime) {
        os++;
      } else {
        minTime = time;
        os = 1;
      }
    }

    // 5. Query blogs by ID
    List<Blog> blogs = inIdOrder(ids, blogRepository.findAllById(ids), Blog::getId);

    enrich(blogs);
    // 6. Wrap and return
    ScrollResult r = new ScrollResult();
    r.setList(blogs);
    r.setOffset(minTime == max ? offset + os : os);
    r.setMinTime(minTime);

    return Result.ok(r);
  }

  private void enrich(List<Blog> blogs) {
    if (blogs.isEmpty()) {
      return;
    }
    List<Long> authorIds =
        blogs.stream().map(Blog::getUserId).distinct().collect(Collectors.toList());
    Map<Long, User> authors =
        userService.listByIds(authorIds).stream().collect(Collectors.toMap(User::getId, u -> u));
    Set<Long> likedIds = new HashSet<>();
    if (UserHolder.getUser() != null) {
      likedIds.addAll(
          blogRepository.selectLikedBlogIds(
              UserHolder.getUser().getId(),
              blogs.stream().map(Blog::getId).collect(Collectors.toList())));
    }
    for (Blog blog : blogs) {
      User author = authors.get(blog.getUserId());
      if (author != null) {
        blog.setName(author.getNickName());
        blog.setIcon(author.getIcon());
      }
      blog.setIsLike(likedIds.contains(blog.getId()));
    }
  }

  private void queryBlogUser(Blog blog) {
    User user = userService.getById(blog.getUserId());
    if (user != null) {
      blog.setName(user.getNickName());
      blog.setIcon(user.getIcon());
    }
  }

  @Override
  public Result queryMyBlog(Integer current) {
    // Get the logged-in user
    UserDTO user = UserHolder.getUser();
    // Query by user
    Page<Blog> page =
        blogRepository.findByUserId(
            user.getId(), pageOf(current, SystemConstants.MAX_PAGE_SIZE, Sort.unsorted()));
    // Get the current page of data
    List<Blog> records = page.getContent();
    return Result.ok(records);
  }

  @Override
  public Result queryBlogByShop(Long shopId, Integer current) {
    if (shopId == null || shopId <= 0 || current < 1 || current > 100000) {
      return Result.fail("shopId and current must be positive integers.");
    }
    Page<Blog> page =
        blogRepository.findByShopId(
            shopId, pageOf(current, 5, Sort.by(Sort.Order.desc("createTime"))));
    return Result.ok(page.getContent(), page.getTotalElements());
  }

  @Override
  public Result searchBlogEvidence(Long shopId, String keyword, Integer current) {
    if (shopId == null
        || shopId <= 0
        || current == null
        || current < 1
        || current > 100000
        || keyword == null
        || keyword.trim().length() < 2
        || keyword.trim().length() > 40) {
      return Result.fail("Invalid shopId, keyword or current.");
    }
    String query = keyword.trim();
    Page<Blog> page =
        blogRepository.searchByShop(
            shopId,
            "%" + query + "%",
            pageOf(current, 5, Sort.by(Sort.Order.desc("createTime"), Sort.Order.desc("id"))));
    return Result.ok(page.getContent(), page.getTotalElements());
  }

  @Override
  public Result queryBlogByUserId(Integer current, Long id) {
    // Query by user
    Page<Blog> page =
        blogRepository.findByUserId(
            id, pageOf(current, SystemConstants.MAX_PAGE_SIZE, Sort.unsorted()));
    // Get the current page of data
    List<Blog> records = page.getContent();
    return Result.ok(records);
  }
}
