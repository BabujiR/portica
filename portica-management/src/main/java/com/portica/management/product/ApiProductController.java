package com.portica.management.product;

import com.portica.common.api.PageResponse;
import com.portica.management.product.ApiProductDtos.ApiProductResponse;
import com.portica.management.product.ApiProductDtos.CreateApiProductRequest;
import com.portica.management.product.ApiProductDtos.UpdateApiProductRequest;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/{org}/apiproducts")
@Tag(name = "API products", description = "Bundles of API proxies that apps subscribe to")
public class ApiProductController {

    private static final Set<String> SORTS = Set.of("name", "displayName", "createdAt", "updatedAt");

    private final ApiProductService products;

    public ApiProductController(ApiProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(#org, 'PRODUCT_READ')")
    @Operation(summary = "List API products")
    public PageResponse<ApiProductResponse> list(@PathVariable String org, @RequestParam(required = false) String q,
                                                 @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        return PageResponses.from(products.list(org, q, PageRequests.requireSortable(pageable, SORTS)),
                ApiProductResponse::from);
    }

    @GetMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'PRODUCT_READ')")
    @Operation(summary = "Get an API product")
    public ApiProductResponse get(@PathVariable String org, @PathVariable String name) {
        return ApiProductResponse.from(products.get(org, name));
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(#org, 'PRODUCT_WRITE')")
    @Operation(summary = "Create an API product")
    public ResponseEntity<ApiProductResponse> create(@PathVariable String org,
                                                     @Valid @RequestBody CreateApiProductRequest request) {
        ApiProduct product = products.create(org, request);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/apiproducts/{name}")
                .buildAndExpand(org, product.getName()).toUri()).body(ApiProductResponse.from(product));
    }

    @PutMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'PRODUCT_WRITE')")
    @Operation(summary = "Replace an API product's definition",
            description = "Takes effect at the gateways on their next sync, for every app holding the product.")
    public ApiProductResponse update(@PathVariable String org, @PathVariable String name,
                                     @Valid @RequestBody UpdateApiProductRequest request) {
        return ApiProductResponse.from(products.update(org, name, request));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'PRODUCT_WRITE')")
    @Operation(summary = "Delete an API product", description = "Refused while app credentials hold it.")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String name) {
        products.delete(org, name);
        return ResponseEntity.noContent().build();
    }
}
