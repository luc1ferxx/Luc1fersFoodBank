-- Demo restaurants price in USD: a restaurant prices its whole menu in one currency (restaurants.currency), and
-- each menu item takes its restaurant's currency.
-- Their pictures are the drawings in frontend/public/static/demo, which the app serves as /static/demo/*.svg,
-- so the demo loads nothing from another host.
INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'demo@example.com', TRUE, '{noop}demo123', 'Demo', 'User'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('demo@example.com')
);


INSERT INTO authorities (email, authority)
SELECT customers.email, 'ROLE_USER'
FROM customers
WHERE LOWER(customers.email) = LOWER('demo@example.com')
  AND NOT EXISTS (
    SELECT 1 FROM authorities WHERE authorities.email = customers.email AND authority = 'ROLE_USER'
);


INSERT INTO authorities (email, authority)
SELECT customers.email, 'ROLE_ADMIN'
FROM customers
WHERE LOWER(customers.email) = LOWER('demo@example.com')
  AND NOT EXISTS (
    SELECT 1 FROM authorities WHERE authorities.email = customers.email AND authority = 'ROLE_ADMIN'
);


INSERT INTO carts (customer_id, total_price, currency)
SELECT id, 0.00, 'USD'
FROM customers
WHERE LOWER(email) = LOWER('demo@example.com')
  AND NOT EXISTS (
      SELECT 1 FROM carts WHERE carts.customer_id = customers.id
  );


INSERT INTO restaurants (name, address, image_url, phone, currency)
SELECT 'Burger King',
       '773 N Mathilda Ave, Sunnyvale, CA 94085',
       '/static/demo/restaurant-burgers.svg',
       '(408) 736-0101',
       'USD'
WHERE NOT EXISTS (SELECT 1 FROM restaurants WHERE name = 'Burger King');


INSERT INTO restaurants (name, address, image_url, phone, currency)
SELECT 'SGD Tofu House',
       '3450 El Camino Real #105, Santa Clara, CA 95051',
       '/static/demo/restaurant-tofu.svg',
       '(408) 261-3030',
       'USD'
WHERE NOT EXISTS (SELECT 1 FROM restaurants WHERE name = 'SGD Tofu House');


INSERT INTO restaurants (name, address, image_url, phone, currency)
SELECT 'Fashion Wok',
       '163 S Murphy Ave, Sunnyvale, CA 94086',
       '/static/demo/restaurant-wok.svg',
       '(408) 739-8866',
       'USD'
WHERE NOT EXISTS (SELECT 1 FROM restaurants WHERE name = 'Fashion Wok');


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Made with white meat chicken, our Chicken Fries are coated in a light crispy breading seasoned with savory spices and herbs.',
       '/static/demo/chicken-fries.svg',
       'Chicken Fries - 9 Pc',
       4.89,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Chicken Fries - 9 Pc'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Our Whopper Sandwich is a 1/4 lb of savory flame-grilled beef topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
       '/static/demo/burger-meal.svg',
       'Whopper Meal',
       10.59,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Whopper Meal'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Our Impossible Whopper Sandwich features a savory flame-grilled patty made from plants topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
       '/static/demo/plant-burger.svg',
       'Impossible Whopper',
       7.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Impossible Whopper'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Say hello to our Hershey Sundae Pie. One part crunchy chocolate crust and one part chocolate creme filling, garnished with a delicious topping and real chocolate chips.',
       '/static/demo/chocolate-pie.svg',
       'Hershey Sundae Pie',
       3.09,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Hershey Sundae Pie'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Our Whopper Sandwich is a 1/4 lb of savory flame-grilled beef topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
       '/static/demo/burger.svg',
       'Whopper',
       6.39,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Whopper'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Our Double Whopper Sandwich is a pairing of two 1/4 lb savory flame-grilled beef patties topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
       '/static/demo/double-burger-meal.svg',
       'Double Whopper Meal',
       11.69,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Double Whopper Meal'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Crispy chicken with mayo and fresh toppings on a toasted bun.',
       '/static/demo/spicy-chicken-sandwich.svg',
       'Spicy Crispy Chicken Sandwich',
       6.09,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Spicy Crispy Chicken Sandwich'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Our Original Chicken Sandwich is lightly breaded and topped with shredded lettuce and creamy mayonnaise on a sesame seed bun.',
       '/static/demo/chicken-sandwich.svg',
       'Original Chicken Sandwich',
       6.09,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Original Chicken Sandwich'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Two 1/4 lb savory flame-grilled beef patties topped with thick-cut smoked bacon, melted American cheese, ketchup, and creamy mayonnaise.',
       '/static/demo/bacon-burger-meal.svg',
       'Bacon King Sandwich Meal',
       12.19,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Bacon King Sandwich Meal'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Cool down with our creamy hand spun Oreo shake.',
       '/static/demo/cookie-shake.svg',
       'Classic Oreo Shake',
       3.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Burger King'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Classic Oreo Shake'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with your choice of meat and mushrooms. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-original.svg',
       'Original Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Original Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with beef, shrimp, and clams. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-combination.svg',
       'Combination Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Combination Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with mussels, shrimp, and clam. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-seafood.svg',
       'Seafood Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Seafood Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Squid, clam, imitation crab, and grilled onions fried in batter.',
       '/static/demo/seafood-pancake.svg',
       'Seafood Pancake',
       20.27,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Seafood Pancake'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with kimchi and your choice of meat. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-kimchi.svg',
       'Kimchi Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Kimchi Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Beef short ribs served with rice and an assortment of kimchi dishes.',
       '/static/demo/short-ribs.svg',
       'Beef Short Ribs',
       29.36,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Beef Short Ribs'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with dumplings, rice cake, and beef. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-dumpling.svg',
       'Dumpling Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Dumpling Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with assorted mushrooms. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-mushroom.svg',
       'Assorted Mushroom Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Assorted Mushroom Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Rice, BBQ beef, and vegetables served in stoneware with an assortment of kimchi dishes.',
       '/static/demo/stone-bowl-rice.svg',
       'BBQ Beef and Vegetables in Stoneware',
       20.27,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'BBQ Beef and Vegetables in Stoneware'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Tofu boiled with ham and cheese. Served with your choice of side and an assortment of kimchi dishes.',
       '/static/demo/tofu-stew-ham-cheese.svg',
       'Ham and Cheese Soft Tofu',
       17.06,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Ham and Cheese Soft Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Medium spicy.',
       '/static/demo/pork-pepper-stir-fry.svg',
       'Stir Fried Pork with Pepper',
       13.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Stir Fried Pork with Pepper'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'A savory eggplant dish with pork, garlic, and cilantro.',
       '/static/demo/eggplant-pork.svg',
       'Eggplant with Minced Pork, Garlic, Cilantro',
       14.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Eggplant with Minced Pork, Garlic, Cilantro'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Mild spicy.',
       '/static/demo/cauliflower-pork.svg',
       'Stir Fried Cauliflower with Pork',
       14.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Stir Fried Cauliflower with Pork'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Mild spicy.',
       '/static/demo/fish-sour-soup.svg',
       'Poached Fish Fillets in Sour Soup',
       17.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Poached Fish Fillets in Sour Soup'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Very spicy.',
       '/static/demo/beef-pepper-stir-fry.svg',
       'Stir Fried Beef with Pepper',
       16.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Stir Fried Beef with Pepper'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Medium spicy.',
       '/static/demo/tripe-tofu-stir-fry.svg',
       'Stir Fried Shredded Tripe with Wugang Tofu',
       19.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Stir Fried Shredded Tripe with Wugang Tofu'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Very spicy.',
       '/static/demo/beef-chili-oil.svg',
       'Poached Sliced Beef in Hot Chili Oil',
       17.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Poached Sliced Beef in Hot Chili Oil'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'With chopped broccoli, peas, carrots, bok choy, and egg.',
       '/static/demo/fried-rice.svg',
       'Fried Rice',
       9.50,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Fried Rice'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'Very spicy.',
       '/static/demo/pepper-eggplant-egg.svg',
       'Smashed Green Pepper, Chinese Eggplant and Preserved Egg',
       11.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Smashed Green Pepper, Chinese Eggplant and Preserved Egg'
  );


INSERT INTO menu_items (description, image_url, name, price, restaurant_id, currency)
SELECT 'A simple greens dish with minced garlic.',
       '/static/demo/garlic-greens.svg',
       'Stir Fried A-Choy with Minced Garlic',
       10.99,
       restaurants.id,
       restaurants.currency
FROM restaurants
WHERE restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
      SELECT 1 FROM menu_items WHERE menu_items.restaurant_id = restaurants.id AND menu_items.name = 'Stir Fried A-Choy with Minced Garlic'
  );
