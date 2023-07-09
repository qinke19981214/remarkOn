package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *

 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService  iFollowService;


    @Override
    public Result queryBlogById(Long id) {

        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("没有该店铺的微博");
        }
        //查询有关用户
        queryBlogUser(blog);
        //查询是否点评
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
          this.queryBlogUser(blog) ;
          this.isBlogLiked(blog);

        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        boolean isSuccess=false;
        //获取用户id
        UserDTO user = UserHolder.getUser();
        String userId = user.getId().toString();
        String key="blog:liked:"+id;
        //判断在redis中是否存在点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        if (score==null){
           //不存在
            isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
              //修改redis
                stringRedisTemplate.opsForZSet().add(key,userId,System.currentTimeMillis());
            }
        }else {
            //存在
            isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId);

            }

        }


        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key="blog:liked:"+id;
        Set<String> listUserId = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(listUserId==null||listUserId.isEmpty()){

            return Result.ok(Collections.emptyList());
        }
        //把字符转化成long
        List<Long> ids = listUserId.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", ids);

        List<UserDTO> userDTOList = userService.query().in("id", ids).last("ORDER BY FIELD (id," + join + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());


        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);

        if (isSuccess){
            //select user_id  from follow where  follow_user_id=?
            List<Follow> userId = iFollowService.query().eq("follow_user_id", user.getId()).list();

            for (Follow follow : userId) {
                //推送
                String key=FEED_KEY+follow.getUserId();
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogFollow(Long max, Integer offset) {
        //获取当前用户id
        Long id = UserHolder.getUser().getId();
        String key=FEED_KEY+id;
        //获取收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples
                = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        if (typedTuples==null||typedTuples.isEmpty()){

            return Result.ok();
        }

        //存放 followId
        List<Long> list=new ArrayList<>(typedTuples.size());
       long  minTime=0;
        int  os=1;
        for (ZSetOperations.TypedTuple<String> tuple:typedTuples){
             //关注人发布信息的id
           Long followId = Long.valueOf(tuple.getValue());
           list.add(followId);
            long score = tuple.getScore().longValue();
            if (minTime==score){
                os++;
            }else {
                minTime=score;
                os=1;
            }
        }

        //查询发布信息
        String idsStr = StrUtil.join(",", list);
        List<Blog> blogList
                = query().in("id", list).last("ORDER BY FIELD (id," + idsStr + ")").list();
        //关联用户的点评
        for (Blog blog : blogList) {
            //查询有关用户
            queryBlogUser(blog);
            //查询是否点评
            isBlogLiked(blog);

        }
        ScrollResult  scrollResult=new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
         scrollResult.setOffset(os);
           return Result.ok(scrollResult);
    }


    private void  queryBlogUser(Blog blog){

        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());


    }

  private void  isBlogLiked(Blog blog){
      UserDTO user = UserHolder.getUser();
      if (user!=null){
          String userId = user.getId().toString();
          String key="blog:liked:"+blog.getId();
          Double score= stringRedisTemplate.opsForZSet().score(key, userId);
          blog.setIsLike(score!=null);
      }
      blog.setIsLike(false);



  }







}
