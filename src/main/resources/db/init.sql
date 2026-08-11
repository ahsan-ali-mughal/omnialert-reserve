CREATE DATABASE IF NOT EXISTS omni_reserve;
USE omni_reserve;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    total_stock INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(30) NOT NULL, -- RESERVED, CONFIRMED, FAILED, CANCELLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_orders_item FOREIGN KEY (item_id) REFERENCES items(id),
    INDEX idx_user_orders (user_id),
    INDEX idx_reservation (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed data for local/dev testing
INSERT INTO users (email, full_name) VALUES
    ('dev@example.com', 'Dev User')
ON DUPLICATE KEY UPDATE email = email;

INSERT INTO items (title, total_stock, price, status) VALUES
    ('Limited Edition Sneaker', 100, 129.99, 'ACTIVE')
ON DUPLICATE KEY UPDATE title = title;
