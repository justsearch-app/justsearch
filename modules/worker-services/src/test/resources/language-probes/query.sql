SELECT customer_name, order_date, total_amount
FROM orders
JOIN customers USING (customer_id)
WHERE order_date BETWEEN '2024-01-01' AND '2024-12-31'
ORDER BY total_amount DESC
LIMIT 50
