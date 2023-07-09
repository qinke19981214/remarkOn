package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

 /*  @Resource
  private    ShopServiceImpl shopService;
   @Resource
   private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;*/

 /*  private ExecutorService  es= Executors.newFixedThreadPool(500);

    @Test
    void saveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,30L);
    }*/


  /*  @Test

    void   idProduct() throws InterruptedException {
        CountDownLatch  countDownLatch=new CountDownLatch(300);
   Runnable runnable=()->{

      for (int i=1;i<=100;i++){

          long id = redisIdWorker.nextId("order");
          System.out.println("id= "+id);

      }
        countDownLatch.countDown();
   };
   long  start=System.currentTimeMillis();
   for (int i=0;i<300;i++){

       es.submit(runnable);
   }
        countDownLatch.await();

      long   end=System.currentTimeMillis();


        System.out.println("运行时间为: "+ (end -start));

    }*/

    /*@Test
    public void  geoTest(){
        //获取所有店铺信息
        List<Shop> shopList = shopService.list();
         //把类型相同存放List集合中
        Map<Long,List<Shop>> map=shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));


      for (Map.Entry<Long,List<Shop>> entry: map.entrySet()){

          Long typeId = entry.getKey();
          String key="shop:geo:"+typeId;
          List<Shop> value = entry.getValue();
          List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
          for (Shop shop : value) {
          locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                  new Point(shop.getX(),shop.getY())) );
          }
          stringRedisTemplate.opsForGeo().add(key,locations);



      }


    }*/


}
