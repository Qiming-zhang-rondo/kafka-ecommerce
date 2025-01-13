package com.example.product.service;

import com.example.common.events.PriceUpdate;
import com.example.common.events.ProductUpdated;
import com.example.product.controller.ProductController;
import com.example.product.kafka.KafkaProductProducer;
import com.example.product.model.Product;
import com.example.product.model.ProductId;
import com.example.product.repository.ProductRepository;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductService implements IProductService {
    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    @Autowired
    private RedisTemplate<String, Product> productRedisTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private KafkaProductProducer kafkaProductService;

    @Override
    public void processCreateProduct(Product product) {
        productRepository.save(product);
    }

    @Override
    public void processProductUpdate(Product product) {
        logger.info("Processing product update for productId: {}", product.getProductId());

        String productKey = "product:" + product.getSellerId() + ":" + product.getProductId();

        try {
            // Step 1: Try to get product from Redis
            Product existingProduct = productRedisTemplate.opsForValue().get(productKey);

            // Step 2: If Redis doesn't have the product, fallback to MySQL
            if (existingProduct == null) {
                existingProduct = productRepository.findById(product.getId())
                        .orElseThrow(() -> new RuntimeException("Product not found: " + product.getId()));
                logger.info("Product loaded from MySQL: {}", product.getProductId());
            } else {
                logger.info("Product loaded from Redis: {}", product.getProductId());
            }

            // Step 3: Update product information
            productRepository.save(product);
            logger.info("Product updated successfully for productId: {}", product.getProductId());

            // Step 4: Update Redis with the new product information
            productRedisTemplate.opsForValue().set(productKey, product);
            logger.info("Product cached in Redis for productId: {}", product.getProductId());

            // Step 5: Create and send ProductUpdated object
            ProductUpdated productUpdated = new ProductUpdated(
                    product.getSellerId(),
                    product.getProductId(),
                    product.getName(),
                    product.getSku(),
                    product.getCategory(),
                    product.getDescription(),
                    product.getPrice(),
                    product.getFreightValue(),
                    product.getStatus(),
                    product.getVersion());

            kafkaProductService.publishProductUpdateEvent(productUpdated);
            logger.info("Product update event sent for productId: {}", product.getProductId());

        } catch (Exception e) {
            logger.error("Error processing product update for productId: {}. Error: {}", product.getProductId(),
                    e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void processPoisonProductUpdate(Product product) {
        kafkaProductService.publishPoisonProductUpdateEvent(product);
    }

    @Override
    public void processPriceUpdate(PriceUpdate priceUpdate) {
        // Redis key for the product
        String productKey = "product:" + priceUpdate.getSellerId() + ":" + priceUpdate.getProductId();

        try {
            // 1. get Product from redis
            Product product = productRedisTemplate.opsForValue().get(productKey);

            if (product == null) {
                // 2. search mysql
                product = productRepository
                        .findById(new ProductId(priceUpdate.getSellerId(), priceUpdate.getProductId()))
                        .orElseThrow(() -> new RuntimeException("Product not found"));

                // 3. save to Redis
                productRedisTemplate.opsForValue().set(productKey, product);
                logger.info("Product loaded from MySQL and cached in Redis: {}", productKey);
            }

            // 4. update Product price and version
            product.setPrice(priceUpdate.getPrice());
            product.setVersion(priceUpdate.getVersion());

            // 5. save to Redis and MySQL
            productRedisTemplate.opsForValue().set(productKey, product);
            productRepository.save(product);

            kafkaProductService.publishPriceUpdateEvent(priceUpdate);

        } catch (Exception e) {
            logger.error("Failed to process price update: {}", e.getMessage());
            throw new RuntimeException("Failed to update product price: " + e.getMessage(), e);
        }
    }

    @Override
    public void processPoisonPriceUpdate(PriceUpdate priceUpdate) {
        kafkaProductService.publishPoisonPriceUpdateEvent(priceUpdate);
    }

    @Override
    public void cleanup() {
        // 清空数据库
        productRepository.deleteAll();

        // 清空 Redis 缓存
        Set<String> keys = productRedisTemplate.keys("product:*");
        if (keys != null && !keys.isEmpty()) {
            productRedisTemplate.delete(keys);
            logger.info("All product entries have been removed from Redis.");
        }
    }

    @Override
    public void reset() {

        productRepository.reset();

        Set<String> keys = productRedisTemplate.keys("product:*");
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                
                Product product = productRedisTemplate.opsForValue().get(key);

                if (product != null) {
                
                    product.setStatus("ACTIVE");
                    product.setVersion("0");
                    productRedisTemplate.opsForValue().set(key, product);
                    logger.info("Product in Redis has been reset: {}", key);
                }
            }
        }
    }

}
