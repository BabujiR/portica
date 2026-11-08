package com.portica.portal.tryit;

import com.portica.portal.api.DeveloperIdentity;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Interactive docs: sends a request to a published API through the gateway and returns what came back. */
@RestController
public class TryItController {

    private final TryItService tryIt;
    private final DeveloperIdentity identity;

    public TryItController(TryItService tryIt, DeveloperIdentity identity) {
        this.tryIt = tryIt;
        this.identity = identity;
    }

    @PostMapping("/api/portal/organizations/{org}/try")
    public ResponseEntity<TryItService.TryItResponse> tryIt(@PathVariable String org, @Valid @RequestBody TryItService.TryItRequest request,
                                                            Authentication authentication) {
        String developer = identity.email(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tryIt.send(org, developer, request));
    }
}
