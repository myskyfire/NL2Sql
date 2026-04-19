import com.nl2sql.common.util.EncryptionUtil;

public class GenerateEncryptedPassword {
    public static void main(String[] args) {
        String password = "123456";
        String encrypted = EncryptionUtil.encrypt(password);
        System.out.println("原始密码: " + password);
        System.out.println("加密后: " + encrypted);
    }
}
