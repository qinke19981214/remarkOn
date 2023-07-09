package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *

 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService  iSeckillVoucherService;
    @Resource
    private RedisIdWorker  redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient  redissonClient;

private  IVoucherOrderService proxy;
    private final  static DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        //脚本初始化
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
     //创建阻塞队列
   // private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
     //获取单线程线程池
    private ExecutorService es= Executors.newSingleThreadExecutor();

   //当前类初始化完成执行
   @PostConstruct
   private void  init(){

    es.submit(new VoucherOrderHandler());
   }





    private class VoucherOrderHandler implements  Runnable{

     String  queueName="stream.orders";

        @Override
        public void run() {
            while (true){
                try {
                    //获取队列订单信息
                    List<MapRecord<String, Object, Object>> recordList = stringRedisTemplate.opsForStream().read(
                            //g1组名,c1消费名
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                        if (recordList==null||recordList.isEmpty()){
                            //再次读
                            continue;
                        }
                     //获取成功
                    MapRecord<String, Object, Object> record = recordList.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //生成订单
                    handlerVoucherOrder(voucherOrder);
                   //ACK确定g1,stream.order ,id处理完

                     stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }


            }


        }

          private void  handlePendingList(){

              while (true) {
                  try {
                      // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                      List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                              Consumer.from("g1", "c1"),
                              StreamReadOptions.empty().count(1),
                              StreamOffset.create(queueName, ReadOffset.from("0"))
                      );
                      // 2.判断订单信息是否为空
                      if (list == null || list.isEmpty()) {
                          // 如果为null，说明没有异常消息，结束循环
                          break;
                      }
                      // 解析数据
                      MapRecord<String, Object, Object> record = list.get(0);
                      Map<Object, Object> value = record.getValue();
                      VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                      // 3.创建订单
                      createVoucherOrder(voucherOrder);
                      // 4.确认消息 XACK
                      stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                  } catch (Exception e) {
                      log.error("处理订单异常", e);
                  }
              }
          }
          }








       /* @Override
        public void run() {
         while (true){
             try {
                 //获取队列订单信息
                 VoucherOrder voucherOrder = orderTasks.take();



                 //生成订单
                 handlerVoucherOrder(voucherOrder);
             } catch (InterruptedException e) {
               log.error("处理订单异常",e);
             }


         }


        }*/



    public   void  handlerVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order" + userId);
        boolean tryLock = false;
        try {
            //第一个参数没有获取锁20秒再次获取,500是释放
            tryLock = lock.tryLock(20,500, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (!tryLock){
            //获取锁失败
            log.error("不允许重复下单");
            return ;
        }

        try {
            //synchronized (userId.toString().intern()){
            //获取动态代理(事务)
            proxy.createVoucherOrder(voucherOrder);
            // }
        }finally {
            //释放锁
            lock.unlock();
        }




    }

    @Override
    public Result seckillVoucher(Long voucherId) {
       proxy = (IVoucherOrderService) AopContext.currentProxy();
        //1执行lua脚本
        //1.1 获取用户id;
        UserDTO user = UserHolder.getUser();
        //获取订单编号
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), user.getId().toString(),
                String.valueOf(orderId));

        int intValue = result.intValue();

        if (intValue!=0){

            return Result.fail(intValue==1?"库存不足":"同一个用户不能重复下单");
        }

        //返回订单编号
        return Result.ok(orderId);

    }






















   /* @Override
    public Result seckillVoucher(Long voucherId) {
        //1执行lua脚本
        //1.1 获取用户id;
        UserDTO user = UserHolder.getUser();
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), user.getId().toString());

        int intValue = result.intValue();

        if (intValue!=0){

            return Result.fail(intValue==1?"库存不足":"同一个用户不能重复下单");
        }

        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setUserId(user.getId());
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);

        //TODO VoucherOrder放入阻塞队列
        orderTasks.add(voucherOrder);
        //返回订单编号
        return Result.ok(orderId);

    }
*/





















   /* @Override
    public Result seckillVoucher(Long voucherId) {
       //查询优惠卷信息
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);

      //判断优惠卷开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){

            return Result.fail("优惠卷活动还未开始");
        }
        //判断优惠卷活动结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){

            return Result.fail("优惠卷活动结束了");
        }
        //判断优惠卷库存
        if (seckillVoucher.getStock()<1){
            return Result.fail("优惠卷库存不足");
        }

        Long userId = UserHolder.getUser().getId();
         //获取锁
        //SimpleRedisLock lock=new SimpleRedisLock("order"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("order" + userId);
        boolean tryLock = false;
        try {
            //第一个参数没有获取锁20秒再次获取,500是释放
            tryLock = lock.tryLock(20,500, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (!tryLock){
          //获取锁失败
          return Result.fail("不允许重复用户下单");
        }

        try {
            //synchronized (userId.toString().intern()){
            //获取动态代理(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

            return proxy.createVoucherOrder(voucherId);
            // }
        }finally {
            //释放锁
            lock.unlock();
        }

    }*/


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){

        //一人一单

        Long userId = voucherOrder.getUserId();
        int  count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        if (count>0){
            log.error("该用户已经买过一次");
        }
        //减库存
        boolean   success=     iSeckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0).update();

        if (!success){

            log.error("减库存失败");
        }


        //保存
        save(voucherOrder);

    }
















}
