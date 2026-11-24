package dev.cdb.driver.web;

import dev.cdb.core.Principal;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.BaselineInfo;
import dev.cdb.protocol.BaselineRequest;
import dev.cdb.protocol.CompareRequest;
import dev.cdb.protocol.CompareResult;
import dev.cdb.protocol.ImageFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ScreenshotController {
    private final SessionManager sessions;

    public ScreenshotController(SessionManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/screenshots/{screenshotId}/image")
    public ResponseEntity<byte[]> image(Principal p, @PathVariable String screenshotId) {
        SessionManager.Image img = sessions.screenshotImage(p, screenshotId);
        return ResponseEntity.ok()
                .contentType(img.format() == ImageFormat.JPEG ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .body(img.bytes());
    }

    @PostMapping("/screenshots/compare")
    public CompareResult compare(Principal p, @RequestBody CompareRequest request) {
        return sessions.compare(p, request);
    }

    @GetMapping("/baselines")
    public List<BaselineInfo> baselines(Principal p) {
        return sessions.baselines(p);
    }

    @PostMapping("/baselines")
    @ResponseStatus(HttpStatus.CREATED)
    public BaselineInfo saveBaseline(Principal p, @RequestBody BaselineRequest request) {
        return sessions.saveBaseline(p, request);
    }
}
