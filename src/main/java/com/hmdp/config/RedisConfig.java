package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient  redissonClient(){
        //配置类
        Config config=new Config();
        //添加redis地址,这里添加是单地址,也可以用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://172.16.66.129:6379").setPassword("123456");
        return Redisson.create(config);
    }
}
