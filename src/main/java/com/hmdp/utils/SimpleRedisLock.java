package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

   private final static String  KEY_PREFIX ="lock:";
    private final static String  ID_PREFIX = UUID.randomUUID().toString(true)+"-";

   private  String  name;
    private StringRedisTemplate stringRedisTemplate;

    private final  static DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        //脚本初始化
        UNLOCK_SCRIPT =new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }








    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }




    @Override
    public boolean tryLock(long timeoutSec) {
       //获取当前线程id
        String threadID = ID_PREFIX+Thread.currentThread().getId();
       Boolean success=   stringRedisTemplate
               .opsForValue()
               .setIfAbsent(KEY_PREFIX+name,threadID+"",timeoutSec, TimeUnit.SECONDS);

       // 防止自动拆箱出现空指针
       return Boolean.TRUE.equals(success);


    }


    @Override
    public void unLock() {
      //使用lua脚本
        stringRedisTemplate
                 .execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),ID_PREFIX+Thread.currentThread().getId());




    }











   /*  @Override
    public void unLock() {
        String threadID = ID_PREFIX+Thread.currentThread().getId();
        //获取key值
        String key = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //删除锁
        if (threadID.equals(key)) {
            stringRedisTemplate.delete(KEY_PREFIX+name);

        }




    }*/
}
