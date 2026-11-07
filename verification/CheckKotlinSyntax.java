import java.nio.file.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtPsiFactory;

class CheckKotlinSyntax {
    public static void main(String[] args) throws Exception {
        var disposable = Disposer.newDisposable();
        int errors = 0;
        try {
            var env = KotlinCoreEnvironment.createForProduction(disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory = new KtPsiFactory(env.getProject(), false);
            try (var paths = Files.walk(Path.of(args[0]))) {
                for (var path : paths.filter(p -> p.toString().endsWith(".kt")).toList()) {
                    var file = factory.createFile(path.getFileName().toString(), Files.readString(path));
                    for (var error : PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class)) {
                        System.out.println(path + ":" + error.getTextOffset() + ": " + error.getErrorDescription());
                        errors++;
                    }
                }
            }
            System.out.println("Kotlin syntax errors: " + errors + " (syntax only, not type/API checking)");
        } finally { Disposer.dispose(disposable); }
        if (errors != 0) System.exit(1);
    }
}
