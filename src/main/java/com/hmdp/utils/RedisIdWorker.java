package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

  //2023-1-1 00:00:00 时间戳
  private final static long  BEGIN_TIMESTAMP=1672531200L;
  //序列号位数
    private final static int  COUNT_BITS=32;
    private StringRedisTemplate stringRedisTemplate;


    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取唯一id
     * @param keyPrefix
     * @return
     */
   public long nextId(String keyPrefix){
      //生成时间戳
       LocalDateTime now = LocalDateTime.now();
       long currTime = now.toEpochSecond(ZoneOffset.UTC);
       long timestamp=currTime-BEGIN_TIMESTAMP;
       //生成序列号
       //取当天填日期,精确到天
       String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
       Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + format);
       //timestamp移动32位


       return timestamp<<COUNT_BITS|count;
   }











}
