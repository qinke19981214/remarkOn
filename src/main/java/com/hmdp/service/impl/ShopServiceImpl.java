package com.hmdp.service.impl;

import ch.qos.logback.core.net.SyslogConstants;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *

 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
   //线程池
   private static final ExecutorService  CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getByIdShop(Long id) {

     // 解决缓存穿透
     //   Shop shop = queryWithPassThrough(id);
     //互斥锁解决击穿
        Shop shop = queryWithMutex(id);

        if (shop==null){
            return Result.fail("没有店铺信息");
        }

        return Result.ok(shop);


    }


    //逻辑删除解决击穿

    public Shop  queryWithLogicalExpire(Long id){
        String key= CACHE_SHOP_KEY+id;
        //从redis取出缓存的数据
        String redisDataString = stringRedisTemplate.opsForValue().get(key);

        if (BeanUtil.isEmpty(redisDataString)){
            //没有预热
            return null;
        }
          //反序列化
        RedisData redisData = JSONUtil.toBean(redisDataString, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (LocalDateTime.now().isAfter(expireTime)){
            //没有过期

            return shop;
        }
        //已经过期了
       //获取锁
        String  lock="lock:shop:"+id;
        Boolean lockBean = tryLock(lock);
        if (lockBean){

            try {


                //获取锁，开启独立线程,实现数据重构
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        this.saveShop2Redis(id, 30L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }


                });
            }catch (Exception e){

                throw  new RuntimeException(e);
            }finally {
                //释放锁
                unLock(lock);
            }

        }
        return shop;









    }








    //解决击穿

    public Shop  queryWithMutex(Long id){


        String key= CACHE_SHOP_KEY+id;
        //从redis取出缓存的数据
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        //判断
        if (BeanUtil.isNotEmpty(shopStr)){
            //缓存中有数据,直接返回
            Shop shop = JSONUtil.toBean(shopStr, Shop.class);
            return shop;
        }
        if (shopStr!=null){

            return null;
        }

      //4重构数据
     //4.1获取互斥锁
     String  lock="lock:shop:"+id;
     Shop shop=null;
     try {


         Boolean lockBoolean = tryLock(lock);
         //4.2判断是否获取到锁
         if (!lockBoolean) {
             //4.2.1没有获取锁休息一段时间
             Thread.sleep(50);
             //递归继续获取锁
             queryWithMutex(id);
         }
         //4.2.2获取到锁

         //4.2.2.1如果没有查询数据库
         shop = getById(id);

         if (shop == null) {
             stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
             return null;
         }

         //4.2.2.2缓存数据到redis中去
         stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
     }catch (Exception e){

         throw new RuntimeException(e);
     }finally {
         //4.3释放锁
         unLock(lock);
     }
     //4.4返回数据
        return shop;
    }



















   //解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key= CACHE_SHOP_KEY+id;
        //从redis取出缓存的数据
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        //判断
        if (BeanUtil.isNotEmpty(shopStr)){
            //缓存中有数据,直接返回
            Shop shop = JSONUtil.toBean(shopStr, Shop.class);
            return shop;
        }

        if (shopStr!=null){

            return null;
        }
        //如果没有查询数据库
        Shop shop = getById(id);

        if (shop==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //缓存数据到redis中去
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }









    //获取锁
    public Boolean  tryLock(String key){

        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 5, TimeUnit.SECONDS);
        //防止拆箱出现空指针异常
        return BooleanUtil.isTrue(flag);
    }


    //释放锁
    public void  unLock(String key){

        stringRedisTemplate.delete(key);
    }











    @Override
    @Transactional
    public Result updateShop(Shop shop) {
      //判断id是否为空
     Long id = shop.getId();
     if (id==null){
         return Result.fail("信息错误");
     }

     //修改数据库
      updateById(shop);
     //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
          //判断是否按距离查寻
        if (x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page =query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
       //计算分页参数
        int from=(current-1)* SystemConstants.DEFAULT_PAGE_SIZE;
        int  end=current*SystemConstants.DEFAULT_PAGE_SIZE;
       //查询redis,按距离分页
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> result = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
       if (result==null){
           return Result.ok(Collections.emptyList());
       }
       List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = result.getContent();
        //防止跳空
        if (list.size()<=from){
            return Result.ok(Collections.emptyList());
        }

        //截取from ~ end
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result1->{
            //获取店铺id
            String idStr = result1.getContent().getName();
            ids.add(Long.valueOf(idStr));
            //获取精度
            Distance distance = result1.getDistance();
            distanceMap.put(idStr,distance);
        });
        //根据id查询店铺
        String shopString = StrUtil.join(",", ids);
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD (id," + shopString + ")").list();
        for (Shop shop : shopList) {

            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }


    //产品预热
    public void saveShop2Redis(Long id ,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //添加到redis中去,添加缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }




}
