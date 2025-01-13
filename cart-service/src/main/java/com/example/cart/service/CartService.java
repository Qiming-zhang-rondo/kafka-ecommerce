package com.example.cart.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.common.driver.MarkStatus;
import com.example.common.driver.TransactionMark;
import com.example.common.driver.TransactionType;
import com.example.common.entities.CartStatus;
import com.example.common.events.PriceUpdate;
import com.example.common.events.ProductUpdated;
import com.example.common.events.ReserveStock;
import com.example.common.requests.CustomerCheckout;
import com.example.cart.kafka.CartKafkaProducer;
import com.example.cart.model.Cart;
import com.example.cart.model.CartItem;
import com.example.cart.model.CartItemId;
import com.example.cart.model.ProductReplica;
import com.example.cart.model.ProductReplicaId;
import com.example.cart.repository.CartItemRepository;
import com.example.cart.repository.CartRepository;
import com.example.cart.repository.ProductReplicaRepository;

import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class CartService implements ICartService {

    @Autowired
    private RedisTemplate<String, Cart> cartRedisTemplate;

    @Autowired
    private RedisTemplate<String, CartItem> cartItemRedisTemplate;

    @Autowired
    private RedisTemplate<String, ProductReplica> productReplicaRedisTemplate;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private ProductReplicaRepository productReplicaRepository;

    @Autowired
    private CartKafkaProducer cartKafkaProducer;

    private static final Logger logger = LoggerFactory.getLogger(CartService.class);

    // @Transactional
    @Override
    public void removeItem(int customerId, int productId, int sellerId) {
        Cart cart = cartRepository.findByCustomerId(customerId);
        if (cart != null) {
            CartItemId itemId = new CartItemId(customerId, sellerId, productId);
            Optional<CartItem> item = cartItemRepository.findById(itemId);

            if (item.isPresent()) {
                cartItemRepository.delete(item.get());
                // cartItemRepository.flush();
            }
        }
    }

    @Transactional
    @Override
    public void seal(Cart cart, boolean cleanItems) {
        logger.info("Sealing cart for customer: {}", cart.getCustomerId());

        String redisKey = "cart:" + cart.getCustomerId();

        cart.setStatus(CartStatus.OPEN);
        cart.setUpdatedAt(LocalDateTime.now());

        if (cleanItems) {
            cart.getItems().clear();
            logger.info("Cart items cleared for customer: {}", cart.getCustomerId());
        }

        cartRepository.save(cart);
        logger.info("Cart sealed and saved to MySQL for customer: {}", cart.getCustomerId());

        try {
            cartRedisTemplate.opsForValue().set(redisKey, cart);
            logger.info("Cart updated in Redis for customer: {}", cart.getCustomerId());
        } catch (Exception e) {
            logger.error("Failed to update Redis cache for customer: {}", cart.getCustomerId(), e);
        }
    }

    @Override
    @Transactional
    public void notifyCheckout(CustomerCheckout customerCheckout) {
        try {
            String cartKey = "cart:" + customerCheckout.getCustomerId();

            // 1. Redis search Cart
            Cart cart = cartRedisTemplate.opsForValue().get(cartKey);

            if (cart == null) {
                cart = cartRepository.findByCustomerId(customerCheckout.getCustomerId());
                if (cart != null) {
                    cartRedisTemplate.opsForValue().set(cartKey, cart);
                    logger.info("Cart cached in Redis for customer: {}", customerCheckout.getCustomerId());
                }
            }

            if (cart == null) {
                throw new Exception("Cart " + customerCheckout.getCustomerId() + " not found");
            }

            if (cart.getStatus() == CartStatus.CHECKOUT_SENT) {
                throw new Exception(
                        "Cart " + customerCheckout.getCustomerId() + " has already been submitted for checkout");
            }

            // 2. Redis search CartItems
            List<CartItem> items = new ArrayList<>();
            List<CartItem> dbItems = cartItemRepository.findByCustomerId(customerCheckout.getCustomerId());

            for (CartItem dbItem : dbItems) {
                String cartItemKey = generateCartItemRedisKey(dbItem);
                CartItem redisItem = cartItemRedisTemplate.opsForValue().get(cartItemKey);

                if (redisItem != null) {
                    items.add(redisItem);
                    logger.info("CartItem found in Redis: {}", redisItem);
                } else {
                    items.add(dbItem);
                    cartItemRedisTemplate.opsForValue().set(cartItemKey, dbItem);
                    logger.info("CartItem cached in Redis: {}", dbItem);
                }
            }

            if (items.isEmpty()) {
                throw new Exception("Cart " + customerCheckout.getCustomerId() + " has no items");
            }

            cart.setStatus(CartStatus.CHECKOUT_SENT);
            cartRepository.save(cart);
            cartRedisTemplate.opsForValue().set(cartKey, cart);

            List<com.example.common.entities.CartItem> cartItems = items.stream()
                    .map(i -> {
                        com.example.common.entities.CartItem cartItem = new com.example.common.entities.CartItem();
                        cartItem.setSellerId(i.getSellerId());
                        cartItem.setProductId(i.getProductId());
                        cartItem.setProductName(i.getProductName() == null ? "" : i.getProductName());
                        cartItem.setUnitPrice(i.getUnitPrice());
                        cartItem.setFreightValue(i.getFreightValue());
                        cartItem.setQuantity(i.getQuantity());
                        cartItem.setVersion(i.getVersion());
                        cartItem.setVoucher(i.getVoucher());
                        return cartItem;
                    })
                    .collect(Collectors.toList());

            LocalDateTime timestamp = LocalDateTime.now();

            ReserveStock checkout = new ReserveStock(
                    timestamp,
                    customerCheckout,
                    cartItems,
                    customerCheckout.getInstanceId());

            cartKafkaProducer.sendReserveStock(checkout);

        } catch (Exception e) {
            throw new RuntimeException("Failed to process checkout: " + e.getMessage(), e);
        }
    }

    private String generateCartItemRedisKey(CartItem item) {
        return String.format("cartItem:%d:%d:%d", item.getId().getCustomerId(), item.getId().getProductId(),
                item.getId().getSellerId());
    }

    @Override
    public void cleanCart() {
        try {
            // 1. clean redis
            Set<String> cartKeys = cartRedisTemplate.keys("cart:*");
            Set<String> cartItemKeys = cartItemRedisTemplate.keys("cartItems:*");
            Set<String> productReplicaKeys = productReplicaRedisTemplate.keys("productReplica:*");

            if (cartKeys != null && !cartKeys.isEmpty()) {
                cartRedisTemplate.delete(cartKeys);
                logger.info("Cleared Redis cart keys: {}", cartKeys.size());
            }

            if (cartItemKeys != null && !cartItemKeys.isEmpty()) {
                cartItemRedisTemplate.delete(cartItemKeys);
                logger.info("Cleared Redis cartItem keys: {}", cartItemKeys.size());
            }

            if (productReplicaKeys != null && !productReplicaKeys.isEmpty()) {
                productReplicaRedisTemplate.delete(productReplicaKeys);
                logger.info("Cleared Redis productReplica keys: {}", productReplicaKeys.size());
            }

            // 2. clean mysql
            cartItemRepository.deleteAll();
            cartRepository.deleteAll();
            productReplicaRepository.deleteAll();

            logger.info("Cleared MySQL data for cart, cartItems, and productReplica.");
        } catch (Exception e) {
            logger.error("Error occurred while cleaning cart data from Redis and MySQL", e);
            throw new RuntimeException("Failed to clean cart data: " + e.getMessage());
        }
    }

    @Override
    public Cart getCart(int customerId) {

        return cartRepository.findByCustomerId(customerId);
    }

    @Transactional
    @Override
    public void processProductUpdated(ProductReplica productUpdated) {
        // 1. Redis Key generate rule
        String redisKey = "productReplica:" + productUpdated.getSellerId() + ":" + productUpdated.getProductId();

        // 2. get product from redis
        ProductReplica existingProduct = productReplicaRedisTemplate.opsForValue().get(redisKey);

        if (existingProduct == null) {
            logger.info("Product not found in Redis, fetching from MySQL...");
            existingProduct = productReplicaRepository.findByProductReplicaId(
                    new ProductReplicaId(productUpdated.getSellerId(), productUpdated.getProductId()));

            if (existingProduct == null) {
                logger.info("Existing product is null, creating new ProductReplica");
                existingProduct = new ProductReplica();
                existingProduct.setSellerId(productUpdated.getSellerId());
                existingProduct.setProductId(productUpdated.getProductId());
                existingProduct.setCreatedAt(LocalDateTime.now());
            }
        } else {
            logger.info("Existing product found in Redis for sellerId: {}, productId: {}",
                    existingProduct.getSellerId(), existingProduct.getProductId());
        }

        existingProduct.setName(productUpdated.getName());
        existingProduct.setPrice(productUpdated.getPrice());
        existingProduct.setVersion(productUpdated.getVersion());
        existingProduct.setActive(productUpdated.isActive());
        existingProduct.setUpdatedAt(LocalDateTime.now());

        productReplicaRepository.save(existingProduct);
        logger.info("ProductReplica updated in MySQL for sellerId: {}, productId: {}",
                existingProduct.getSellerId(), existingProduct.getProductId());

        productReplicaRedisTemplate.opsForValue().set(redisKey, existingProduct);
        logger.info("ProductReplica updated in Redis for sellerId: {}, productId: {}",
                existingProduct.getSellerId(), existingProduct.getProductId());
    }

    @Override
    @Transactional
    public void processPriceUpdate(PriceUpdate priceUpdate) {
        String baseKey = "cartItem:" + priceUpdate.getSellerId() + ":" + priceUpdate.getProductId();
        String productReplicaKey = "productReplica:" + priceUpdate.getSellerId() + ":" + priceUpdate.getProductId();

        try {
            // Step 1: get ProductReplica from redis
            ProductReplica product = productReplicaRedisTemplate.opsForValue().get(productReplicaKey);

            if (product == null) {
                
                product = productReplicaRepository.findByProductReplicaId(
                        new ProductReplicaId(priceUpdate.getSellerId(), priceUpdate.getProductId()));

                if (product == null) {
                    throw new IllegalArgumentException(
                            "Product not found: " + priceUpdate.getSellerId() + "-" + priceUpdate.getProductId());
                }

                // save ProductReplica into Redis
                productReplicaRedisTemplate.opsForValue().set(productReplicaKey, product);
                logger.info("ProductReplica loaded from MySQL and cached in Redis: {}", productReplicaKey);
            }

            // Step 2: find CartItems
            List<CartItem> cartItems = new ArrayList<>();
            List<CartItem> dbCartItems = cartItemRepository.findBySellerIdAndProductId(
                    priceUpdate.getSellerId(), priceUpdate.getProductId());

            for (CartItem cartItem : dbCartItems) {
                int customerId = cartItem.getId().getCustomerId();
                String cartItemKey = baseKey + ":" + customerId;

                // Redis
                CartItem cachedCartItem = cartItemRedisTemplate.opsForValue().get(cartItemKey);

                if (cachedCartItem != null) {
                    cartItems.add(cachedCartItem);
                    logger.info("CartItem loaded from Redis: {}", cartItemKey);
                } else {
                    // mysql
                    cartItems.add(cartItem);

                 
                    cartItemRedisTemplate.opsForValue().set(cartItemKey, cartItem);
                    logger.info("CartItem loaded from MySQL and cached in Redis: {}", cartItemKey);
                }
            }

            // Step 3: update price and voucher
            for (CartItem cartItem : cartItems) {
                float oldPrice = cartItem.getUnitPrice();
                cartItem.setUnitPrice(priceUpdate.getPrice());
                cartItem.setVoucher(cartItem.getVoucher() + (oldPrice - priceUpdate.getPrice()));

                // save to redis
                String cartItemKey = baseKey + ":" + cartItem.getId().getCustomerId();
                cartItemRedisTemplate.opsForValue().set(cartItemKey, cartItem);
            }

            // Step 4: save to mysql
            cartItemRepository.saveAll(cartItems);
            logger.info("Updated CartItems saved to MySQL.");

            // Step 5:send TransactionMark to Kafka
            TransactionMark transactionMark = new TransactionMark(
                    priceUpdate.getInstanceId(),
                    TransactionType.PRICE_UPDATE,
                    priceUpdate.getSellerId(),
                    MarkStatus.SUCCESS,
                    "cart");

            cartKafkaProducer.sendPriceUpdateTransactionMark(transactionMark);

        } catch (Exception e) {
            throw new RuntimeException("Failed to process price update: " + e.getMessage(), e);
        }
    }

    @Override
    public void reset() {
        try {
            // 1. clean redis
            Set<String> cartKeys = cartRedisTemplate.keys("cart:*");
            if (cartKeys != null && !cartKeys.isEmpty()) {
                cartRedisTemplate.delete(cartKeys);
                logger.info("Cleared Redis cart keys: {}", cartKeys.size());
            }

            Set<String> cartItemKeys = cartItemRedisTemplate.keys("cartItems:*");
            if (cartItemKeys != null && !cartItemKeys.isEmpty()) {
                cartItemRedisTemplate.delete(cartItemKeys);
                logger.info("Cleared Redis cartItem keys: {}", cartItemKeys.size());
            }

            // 2. reset Redis
            Set<String> productReplicaKeys = productReplicaRedisTemplate.keys("productReplica:*");
            if (productReplicaKeys != null && !productReplicaKeys.isEmpty()) {
                productReplicaRedisTemplate.delete(productReplicaKeys);
                logger.info("Cleared Redis productReplica keys: {}", productReplicaKeys.size());
            }

            // 3. clean mysql
            cartItemRepository.deleteAll();
            cartRepository.deleteAll();
            logger.info("Cleared MySQL data for cart and cartItems.");

            // 4. reset mysql
            productReplicaRepository.reset();
            logger.info("Reset MySQL productReplica table.");

        } catch (Exception e) {
            logger.error("Error occurred while resetting data in Redis and MySQL", e);
            throw new RuntimeException("Failed to reset data: " + e.getMessage());
        }
    }

    @Override
    public void processPoisonProductUpdated(ProductUpdated productUpdated) {
        TransactionMark transactionMark = new TransactionMark(
                productUpdated.getVersion(),
                TransactionType.UPDATE_PRODUCT,
                productUpdated.getSellerId(),
                MarkStatus.ABORT,
                "cart");
        cartKafkaProducer.sendPoisonProductUpdated(transactionMark);
    }

    @Override
    public void processPoisonPriceUpdate(PriceUpdate priceUpdated) {
        TransactionMark transactionMark = new TransactionMark(
                priceUpdated.getInstanceId(),
                TransactionType.PRICE_UPDATE,
                priceUpdated.getSellerId(),
                MarkStatus.ABORT,
                "cart");
        cartKafkaProducer.sendPoisonPriceUpdate(transactionMark);
    }

    public void processPoisonCheckout(CustomerCheckout customerCheckout, MarkStatus status) {
        TransactionMark transactionMark = new TransactionMark(
                customerCheckout.getInstanceId(),
                TransactionType.CUSTOMER_SESSION,
                customerCheckout.getCustomerId(),
                status,
                "cart");

        cartKafkaProducer.sendPoisonCheckout(transactionMark);
    }

}
