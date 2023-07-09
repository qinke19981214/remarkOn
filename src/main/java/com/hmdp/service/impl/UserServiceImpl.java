package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *

 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证号码是否正确
        if (RegexUtils.isPhoneInvalid(phone)){
            //如果不合格,返回不合格信息
            return Result.fail("号码不正确");
        }

      //生成验证码
        String code = RandomUtil.randomNumbers(6);

     //把验证码保存到session中
    // session.setAttribute("code",code);

     //把code保存到redis中 ,设置过期时间
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码

      log.info("验证码是:{}",code);

      return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
       //验证号码是否正确
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //如果不合格,返回不合格信息
            return Result.fail("号码不正确");
        }

        //判断验证码
       // Object code = session.getAttribute("code");
        //从redis获取code
        Object code=  stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+loginForm.getPhone());


        if (code==null||!code.toString().equals(loginForm.getCode())){
            return Result.fail("验证码不正确");
        }

        //查询phone信息

       User user = query().eq("phone", loginForm.getPhone()).one();

        if (user==null){
          //创建user
            user = createByPhoneUser(loginForm.getPhone());
        }

        //1把userDTO保存到redis中
        //1.1随机生成token ,作为登入令牌
       String token=  UUID.randomUUID().toString(true);
        //1.2将userDTO对象转化HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
        .setFieldValueEditor((s1,s2)->s2.toString()));
        //1.3存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userDTOMap);
        //设置token有效时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);



        //保存user到session中
        //session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));

         //返回token
        return Result.ok(token);
    }
    //签到
    @Override
    public Result sign() {
      //获取当前用户
      Long userId = UserHolder.getUser().getId();
      //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));

      //获取key
      String  key=USER_SIGN_KEY+userId+yyyyMM;

      //获取当月第几天
        int dayOfMonth = now.getDayOfMonth();

        //Redis中设置bitMap
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }


    //签到统计
    @Override
    public Result signCount() {

        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        //获取key
        String  key=USER_SIGN_KEY+userId+yyyyMM;

        //获取当月第几天
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)

        );

        if (result==null|| result.isEmpty()){
            return Result.ok(0);
        }
       //因为执行一个get
        Long num = result.get(0);

        if (num==null){

            return Result.ok(0);
        }

       int count=0;

        while (true){

          if ((num&1)==0){
              //说明没有签到
              break;
          }else {
              //说明签到,计数器加一
              count++;
          }

         //向右移位一位
          num>>>=1;
        }
        return Result.ok(count);
    }

    private User createByPhoneUser(String phone) {

     User user=new User();
     user.setPhone(phone);
     user.setNickName("user_"+RandomUtil.randomString(10));
      //保存user
     save(user);

     return user;

    }








}
