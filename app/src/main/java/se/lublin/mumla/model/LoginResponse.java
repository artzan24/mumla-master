package se.lublin.mumla.model;
import java.util.List;

public class LoginResponse {
    private boolean status;
    private String message;
    private Profile profile;
    private List<Channel> allowed_channels;

    public boolean isStatus() { return status; }
    public String getMessage() { return message; }
    public Profile getProfile() { return profile; }
    public List<Channel> getAllowed_channels() { return allowed_channels; }
}