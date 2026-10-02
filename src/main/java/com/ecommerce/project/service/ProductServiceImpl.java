package com.ecommerce.project.service;

import java.io.IOException;
import java.util.List;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.Category;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.payload.ProductDTO;
import com.ecommerce.project.payload.ProductResponse;
import com.ecommerce.project.repositories.CategoryRepository;
import com.ecommerce.project.repositories.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

        private final ProductRepository productRepository;

        private final CategoryRepository categoryRepository;

        private final ModelMapper modelMapper;

        private final FileService fileService;

        @Value("${project.image}")
        private String path;

        @Override
        public ProductDTO addProduct(Long categoryId, ProductDTO productDTO) {
                Category category = categoryRepository.findById(categoryId)
                                .orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));

                boolean isProductNotPresent = true;

                List<Product> products = category.getProducts();
                for (Product value : products) {
                        if (value.getProductName().equals(productDTO.getProductName())) {
                                isProductNotPresent = false;
                                break;
                        }
                }

                if (isProductNotPresent) {
                        Product product = modelMapper.map(productDTO, Product.class);
                        product.setProductId(null);
                        product.setImage("default.png");
                        product.setCategory(category);
                        double specialPrice = product.getPrice() -
                                        ((product.getDiscount() * 0.01) * product.getPrice());
                        product.setSpecialPrice(specialPrice);
                        Product savedProduct;
                        try {
                                savedProduct = productRepository.save(product);
                        } catch (DataIntegrityViolationException e) {
                                throw new APIException("Product already exist!!");
                        }
                        return modelMapper.map(savedProduct, ProductDTO.class);
                } else {
                        throw new APIException("Product already exist!!");
                }
        }

        @Override
        public ProductResponse getAllProducts(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
                Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                                ? Sort.by(sortBy).ascending()
                                : Sort.by(sortBy).descending();

                Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
                Page<Product> pageProducts = productRepository.findAll(pageDetails);

                List<Product> products = pageProducts.getContent();

                List<ProductDTO> productDTOS = products.stream()
                                .map(product -> modelMapper.map(product, ProductDTO.class))
                                .toList();

                ProductResponse productResponse = new ProductResponse();
                productResponse.setContent(productDTOS);
                productResponse.setPageNumber(pageProducts.getNumber());
                productResponse.setPageSize(pageProducts.getSize());
                productResponse.setTotalElements(pageProducts.getTotalElements());
                productResponse.setTotalPages(pageProducts.getTotalPages());
                productResponse.setLastPage(pageProducts.isLast());
                return productResponse;
        }

        @Override
        public ProductResponse searchByCategory(Long categoryId, Integer pageNumber, Integer pageSize, String sortBy,
                        String sortOrder) {
                Category category = categoryRepository.findById(categoryId)
                                .orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));

                Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                                ? Sort.by(sortBy).ascending()
                                : Sort.by(sortBy).descending();

                Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
                Page<Product> pageProducts = productRepository.findByCategoryOrderByPriceAsc(category, pageDetails);

                List<Product> products = pageProducts.getContent();

                if (products.isEmpty()) {
                        throw new APIException(category.getCategoryName() + " category does not have any products");
                }

                List<ProductDTO> productDTOS = products.stream()
                                .map(product -> modelMapper.map(product, ProductDTO.class))
                                .toList();

                ProductResponse productResponse = new ProductResponse();
                productResponse.setContent(productDTOS);
                productResponse.setPageNumber(pageProducts.getNumber());
                productResponse.setPageSize(pageProducts.getSize());
                productResponse.setTotalElements(pageProducts.getTotalElements());
                productResponse.setTotalPages(pageProducts.getTotalPages());
                productResponse.setLastPage(pageProducts.isLast());
                return productResponse;
        }

        @Override
        public ProductResponse searchProductByKeyword(String keyword, Integer pageNumber, Integer pageSize,
                        String sortBy, String sortOrder) {
                Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                                ? Sort.by(sortBy).ascending()
                                : Sort.by(sortBy).descending();

                Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
                Page<Product> pageProducts = productRepository.findByProductNameLikeIgnoreCase('%' + keyword + '%',
                                pageDetails);

                List<Product> products = pageProducts.getContent();
                List<ProductDTO> productDTOS = products.stream()
                                .map(product -> modelMapper.map(product, ProductDTO.class))
                                .toList();

                if (products.isEmpty()) {
                        throw new APIException("Products not found with keyword: " + keyword);
                }

                ProductResponse productResponse = new ProductResponse();
                productResponse.setContent(productDTOS);
                productResponse.setPageNumber(pageProducts.getNumber());
                productResponse.setPageSize(pageProducts.getSize());
                productResponse.setTotalElements(pageProducts.getTotalElements());
                productResponse.setTotalPages(pageProducts.getTotalPages());
                productResponse.setLastPage(pageProducts.isLast());
                return productResponse;
        }

        @Override
        public ProductDTO updateProduct(Long productId, ProductDTO productDTO) {
                Product productFromDb = productRepository.findById(productId)
                                .orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

                Product product = modelMapper.map(productDTO, Product.class);

                productFromDb.setProductName(product.getProductName());
                productFromDb.setDescription(product.getDescription());
                productFromDb.setQuantity(product.getQuantity());
                productFromDb.setDiscount(product.getDiscount());
                productFromDb.setPrice(product.getPrice());
                double specialPrice = productFromDb.getPrice() -
                                ((productFromDb.getDiscount() * 0.01) * productFromDb.getPrice());
                productFromDb.setSpecialPrice(specialPrice);

                Product savedProduct;
                try {
                        savedProduct = productRepository.save(productFromDb);
                } catch (DataIntegrityViolationException e) {
                        throw new APIException(
                                        "Product with the name " + productFromDb.getProductName()
                                                        + " already exists in this category!!!");
                }

                return modelMapper.map(savedProduct, ProductDTO.class);
        }

        @Override
        public ProductDTO deleteProduct(Long productId) {
                Product product = productRepository.findById(productId)
                                .orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

                productRepository.delete(product);
                return modelMapper.map(product, ProductDTO.class);
        }

        @Override
        public ProductDTO updateProductImage(Long productId, MultipartFile image) throws IOException {
                Product productFromDb = productRepository.findById(productId)
                                .orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

                String fileName = fileService.uploadImage(path, image);
                productFromDb.setImage(fileName);

                Product updatedProduct = productRepository.save(productFromDb);
                return modelMapper.map(updatedProduct, ProductDTO.class);
        }

}