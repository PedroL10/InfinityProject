# Notas de Estudo — Spring Boot (projeto Infinite)

Anotações pessoais sobre conceitos estudados neste projeto, para consulta e revisão.

---

## 1. Por que separar `Service` e `ServiceImpl`?

### Contexto
No projeto, `CategoryService` é uma **interface** e `CategoryServiceImpl` é a **classe que a implementa**. O `CategoryController` depende do tipo `CategoryService` (a interface), não de `CategoryServiceImpl` diretamente.

```java
// CategoryService.java — o contrato
public interface CategoryService {
    List<Category> getAllCategories();
    void createCategory(Category category);
    String deleteCategory(Long categoryId);
    Category updateCategory(Category category, Long categoryId);
}

// CategoryServiceImpl.java — a implementação real
@Service
public class CategoryServiceImpl implements CategoryService {
    @Override
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }
    // ...
}

// CategoryController.java — depende da interface
@Autowired
private CategoryService categoryService;
```

### Explicação
- A **interface** define *o que* o serviço faz (assinaturas dos métodos).
- A **implementação** define *como* ele faz (a lógica de verdade).
- O Controller conhece só o contrato; o Spring, em tempo de execução, injeta automaticamente a implementação concreta (`CategoryServiceImpl`) no campo do tipo `CategoryService`.

### Vantagens
1. **Desacoplamento** — quem usa o serviço não depende de detalhes de implementação; a implementação pode ser trocada sem alterar quem a consome.
2. **Testabilidade** — é fácil criar um *mock* de uma interface (ex.: com Mockito) para testar o Controller isoladamente, sem precisar de banco de dados real.
3. **Convenção Spring** — é o padrão idiomático em arquitetura em camadas (Controller → Service → Repository).
4. **Múltiplas implementações futuras** — permite trocar/adicionar implementações (ex.: uma versão com cache) sem tocar no restante do sistema.

### Isso é obrigatório?
**Não.** O Spring não exige essa separação — funcionaria também com uma única classe `@Service` sem interface. É uma convenção histórica e uma boa prática de design ("programe para interfaces"), especialmente útil quando há testes ou possibilidade real de múltiplas implementações. No projeto atual, com uma única implementação, o ganho principal e imediato é testabilidade e organização em camadas.

### 📌 Resumo
A interface `CategoryService` define o contrato (quais métodos existem) e a classe `CategoryServiceImpl` contém a implementação real desses métodos; o Controller é escrito para depender apenas da interface, e o Spring, em tempo de execução, injeta a implementação concreta automaticamente — isso desacopla quem usa o serviço de como ele funciona por dentro, facilita trocar a implementação no futuro sem alterar quem a consome, e sobretudo facilita testes unitários (é fácil criar um mock de uma interface); no projeto, com uma única implementação, o ganho imediato é mais testabilidade e organização em camadas do que polimorfismo real, e essa separação é uma convenção comum em Spring, não uma exigência do framework.

---

## 2. Por que usar `ResponseEntity`?

### Contexto
No `CategoryController`, os métodos retornam `ResponseEntity<T>` em vez do objeto puro:

```java
@GetMapping("/public/categories")
public ResponseEntity<List<Category>> getAllCategories(){
    List<Category> categories = categoryService.getAllCategories();
    return new ResponseEntity<>(categories, HttpStatus.OK);
}

@DeleteMapping("/admin/categories/{categoryId}")
public ResponseEntity<String> deleteCategory(@PathVariable Long categoryId){
    try {
        String status = categoryService.deleteCategory(categoryId);
        return ResponseEntity.status(HttpStatus.OK).body(status);
    } catch (ResponseStatusException e){
        return new ResponseEntity<>(e.getReason(), e.getStatusCode());
    }
}
```

### Explicação
Uma resposta HTTP tem três partes: **status code** (200, 201, 404...), **headers** e **corpo**. Sem `ResponseEntity`, o Spring sempre devolveria status 200 por padrão, independente do que aconteceu. `ResponseEntity<T>` permite controlar essas três partes explicitamente e **dinamicamente**, a cada requisição.

### Formas de construir
```java
new ResponseEntity<>(corpo, HttpStatus.OK);           // construtor direto
ResponseEntity.status(HttpStatus.OK).body(corpo);      // builder fluente
ResponseEntity.ok(corpo);                               // atalho para 200
ResponseEntity.notFound().build();                      // atalho para 404
ResponseEntity.created(uri).build();                    // atalho para 201 + header Location
```

### Por que não `@ResponseStatus`?
`@ResponseStatus` fixa o status no método (sempre o mesmo, não importa o resultado). `ResponseEntity` permite decidir o status em tempo de execução — essencial quando o mesmo endpoint pode retornar sucesso ou erro (ex.: 200 se encontrou, 404 se não encontrou).

### Observação sobre o `try/catch`
No projeto, exceções `ResponseStatusException` lançadas no `CategoryServiceImpl` são capturadas manualmente no Controller e convertidas em `ResponseEntity`. Isso funciona, mas é redundante: o Spring já sabe tratar `ResponseStatusException` nativamente. Uma evolução comum é usar um `@ExceptionHandler`/`@ControllerAdvice` global para eliminar o `try/catch` repetido em cada método.

### 📌 Resumo
`ResponseEntity` existe porque uma resposta HTTP é mais do que o corpo JSON — inclui também o status code e headers, e sem ela o Spring sempre devolveria 200 por padrão; usando `ResponseEntity<T>` (seja via construtor, `ResponseEntity.status(status).body(corpo)` ou atalhos como `ResponseEntity.ok(corpo)`) o controller decide dinamicamente, a cada requisição, qual corpo e qual status devolver, o que é essencial numa API REST onde o resultado varia (sucesso, não encontrado, erro de validação etc.); no `CategoryController`, isso aparece nos blocos `try/catch` que convertem uma `ResponseStatusException` lançada no service em um `ResponseEntity` com o status correto, embora esse padrão possa futuramente ser simplificado com um `@ExceptionHandler` global em vez de repetir o `try/catch` em cada método.

---

## 3. Bug: POST em `/api/public/categories` lançava `StaleObjectStateException`

### Contexto
Ao dar POST em `/api/public/categories` com `{"categoryName":"Sports & Fitness"}`, a aplicação lançava:
```
org.hibernate.orm.ObjectOptimisticLockingFailureException: Row was already updated or deleted by another transaction for entity [com.ecommerce.project.model.Category with id '1']
```
mesmo sendo a primeira categoria criada no banco (H2 em memória recém-criado).

Código problemático (`CategoryServiceImpl.java`, antes do fix):
```java
private Long nextId = 1L;
// ...
@Override
public void createCategory(Category category) {
    category.setCategoryId(nextId++);   // <-- atribuição manual do ID
    categoryRepository.save(category);
}
```

E a entidade:
```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long categoryId;
```

### Explicação
`GenerationType.IDENTITY` significa que **o banco de dados** gera o ID no momento do `INSERT` — a aplicação não deveria atribuir esse valor manualmente. O código antigo, resquício de uma versão em que as categorias eram guardadas numa lista em memória (havia até um comentário `//private List<Category> categories = new ArrayList<>()`), continuava setando `categoryId` manualmente antes de chamar `save()`.

Quando o Spring Data JPA recebe uma entidade para salvar cujo `@Id` já não é nulo, ele assume que ela **já existe** no banco e, em vez de fazer `INSERT` (via `persist`), tenta um `UPDATE` (via `merge`) — procurando no banco uma linha com aquele ID. Como a linha nunca existiu, o Hibernate interpreta isso como "a linha existia e foi alterada/apagada por outra transação" e lança `StaleObjectStateException`/`ObjectOptimisticLockingFailureException`, mesmo sem haver campo `@Version` na entidade.

### Correção
Remover a atribuição manual do ID e o campo `nextId`, deixando o banco gerar o ID:
```java
@Override
public void createCategory(Category category) {
    categoryRepository.save(category);
}
```

### 📌 Resumo
O erro acontecia porque `CategoryServiceImpl.createCategory` atribuía manualmente um ID (`category.setCategoryId(nextId++)`) antes de salvar, mesmo a entidade `Category` usando `@GeneratedValue(strategy = GenerationType.IDENTITY)` — ou seja, o ID deveria ser gerado pelo próprio banco no INSERT; ao ver um `@Id` já preenchido, o Spring Data JPA/Hibernate assume que a entidade já existe e tenta um `UPDATE` (via `merge`) em vez de um `INSERT` (via `persist`), e como nenhuma linha com aquele ID existia de fato, o Hibernate lança `StaleObjectStateException` interpretando isso como se a linha tivesse sido alterada por outra transação; a correção foi remover a atribuição manual do ID (e o campo `nextId`, resquício de uma implementação antiga em lista), deixando o banco gerar o identificador normalmente.

---

## 4. Tratamento de exceções: `ResourceNotFoundException`, `APIException` e `MyGlobalExceptionHandler`

### Contexto
O projeto centraliza o tratamento de erros em vez de usar `try/catch` em cada endpoint do controller. Três classes trabalham juntas, todas em `com.ecommerce.project.exceptions`:

```java
// Exceção de domínio para "não encontrado"
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceName, String field, Long fieldId) {
        super(String.format("%s not found with %s: %d", resourceName, field, fieldId));
        ...
    }
}

// Exceção de domínio genérica para regras de negócio violadas
public class APIException extends RuntimeException {
    public APIException(String message) { super(message); }
}

// Interceptador global
@RestControllerAdvice
public class MyGlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> myResourceNotFoundException(ResourceNotFoundException e) {
        return new ResponseEntity<>(e.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(APIException.class)
    public ResponseEntity<String> myAPIException(APIException e) {
        return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> myMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        // monta um mapa campo -> mensagem de erro, a partir das violações do @Valid
    }
}
```

**Onde são lançadas** — em `CategoryServiceImpl`:
```java
if (categories.isEmpty())
    throw new APIException("No category created till now.");
...
if (savedCategory != null)
    throw new APIException("Category with the name " + category.getCategoryName() + " already exists !!!");
...
Category category = categoryRepository.findById(categoryId)
        .orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));
```
E o `CategoryController` não captura mais nada — os métodos deixam as exceções subirem livremente.

### Explicação
- `ResourceNotFoundException` e `APIException` são **exceções de domínio puras**: não conhecem HTTP, `HttpStatus` nem `ResponseEntity`, só carregam uma mensagem. `ResourceNotFoundException` é usada especificamente quando uma busca (`findById`, etc.) não encontra o recurso; `APIException` é o "balde genérico" para outras regras de negócio violadas (lista vazia, nome duplicado).
- `MyGlobalExceptionHandler`, anotada com `@RestControllerAdvice`, intercepta exceções lançadas por **qualquer** `@RestController` da aplicação (não só `CategoryController`). Cada método anotado com `@ExceptionHandler(TipoDaExceção.class)` funciona como um "catch" registrado globalmente: quando uma exceção daquele tipo sobe sem ser capturada em nenhum controller, o Spring a intercepta e executa o handler correspondente, convertendo-a no `ResponseEntity` (status + corpo) correto.
- `MethodArgumentNotValidException` é diferente: não é lançada manualmente — é disparada automaticamente pelo Spring quando `@Valid` (usado em `createCategory`/`updateCategory` no controller) detecta violação de validação Bean Validation no corpo da requisição, antes mesmo do método do controller ser executado.

### Fluxo completo (exemplo: DELETE com ID inexistente)
1. `DELETE /api/admin/categories/999` chega no controller.
2. `categoryService.deleteCategory(999)` → `findById(999)` retorna vazio.
3. `.orElseThrow(...)` lança `ResourceNotFoundException`.
4. A exceção sobe sem ser capturada em nenhum lugar do controller/service.
5. O Spring intercepta e encontra `myResourceNotFoundException` no `MyGlobalExceptionHandler`.
6. O handler devolve `ResponseEntity<>(mensagem, HttpStatus.NOT_FOUND)` → cliente recebe 404 com a mensagem.

Sem esse handler, a exceção não tratada resultaria em **500 Internal Server Error** genérico — semanticamente errado para um caso de "não encontrado".

### Por que essa arquitetura em vez de `try/catch` por endpoint?
Centraliza a regra "que status HTTP corresponde a cada tipo de erro" em um único lugar; qualquer novo endpoint/controller já fica automaticamente coberto, sem precisar escrever tratamento de exceção nenhum; e os services ficam desacoplados do mundo HTTP, lançando apenas exceções de domínio.

### 📌 Resumo
O projeto usa três peças que trabalham juntas para tratar erros de forma centralizada: `ResourceNotFoundException` e `APIException` são exceções de domínio (RuntimeException customizadas) lançadas pelos métodos de `CategoryServiceImpl` quando uma regra de negócio é violada — a primeira para "recurso não encontrado" (ex.: `findById` vazio), a segunda para outras regras (lista vazia, nome duplicado) — e nenhuma das duas sabe nada sobre HTTP, apenas carregam uma mensagem; quem converte essas exceções em respostas HTTP reais é `MyGlobalExceptionHandler`, uma classe anotada com `@RestControllerAdvice` que intercepta exceções lançadas por qualquer controller da aplicação, com um método `@ExceptionHandler` por tipo de exceção (404 para `ResourceNotFoundException`, 400 para `APIException`, e um tratamento especial para `MethodArgumentNotValidException`, disparada automaticamente pelo Spring quando `@Valid` falha na validação do corpo da requisição); essa arquitetura elimina a repetição de `try/catch` em cada endpoint do controller e centraliza, num único lugar, a regra de qual status HTTP corresponde a cada tipo de erro.

---

## 5. Por que usar `Map<String, String>` no handler de validação

### Contexto
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<Map<String, String>> myMethodArgumentNotValidException(MethodArgumentNotValidException e) {
    Map<String, String> response = new HashMap<>();
    e.getBindingResult().getAllErrors().forEach(err -> {
        String fieldName = ((FieldError) err).getField();
        String message = err.getDefaultMessage();
        response.put(fieldName, message);
    });
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
}
```
`Category.categoryName` tem `@NotBlank` e `@Size(min = 5, ...)`. Quando `@Valid` falha no controller, esse handler monta um `Map` associando cada nome de campo à sua mensagem de erro.

### Explicação
- `Map<K, V>` guarda pares **chave → valor** com chaves únicas — diferente de `List`/array, que guardam apenas uma sequência posicional. Aqui a relação natural é **campo → mensagem de erro**, com quantidade variável de entradas (depende de quantos campos falharam).
- `Map` é interface, `HashMap` é a implementação concreta usada (`new HashMap<>()`) — mesmo princípio de "programar para a interface" da seção 1 (`CategoryService`/`CategoryServiceImpl`). Poderia trocar por `LinkedHashMap` (preserva ordem de inserção) ou `TreeMap` (ordena por chave) sem mudar o resto do código.
- **Por que não `List<String>`?** Perderia a associação entre campo e mensagem — o cliente não saberia qual campo gerou qual erro, ruim para exibir erro embaixo do input certo num formulário.
- **Por que não uma classe própria (`List<FieldError>` customizada)?** É uma alternativa válida e mais expressiva, mas exige criar/manter uma classe extra só para dois campos simples (campo + mensagem); `Map` é a solução direta para esse caso simples.
- **Serialização automática**: o Jackson (usado pelo Spring Boot) converte `Map<String, String>` diretamente num objeto JSON `{"campo": "mensagem"}`, formato conveniente para o cliente acessar `erro.categoryName` diretamente.
- **Limitação**: como `Map` não permite chaves duplicadas, se dois erros ocorrerem no mesmo campo (ex.: `@NotBlank` e `@Size` falham juntos), `put()` sobrescreve e só a última mensagem sobrevive.

### 📌 Resumo
`Map<String, String>` foi escolhido porque o problema tem exatamente essa forma — associação chave única → valor (nome do campo → mensagem de erro), com quantidade variável de entradas; a variável é declarada como a interface `Map` mas instanciada como `HashMap` (mesmo princípio de programar para a interface já visto entre `CategoryService`/`CategoryServiceImpl`), podendo trocar por `LinkedHashMap` ou `TreeMap` sem alterar o resto do código; alternativas como `List<String>` ou array perderiam a associação entre campo e mensagem, enquanto uma classe própria seria mais expressiva mas exigiria criar uma classe extra só para isso; e o Jackson converte esse `Map` automaticamente num objeto JSON `{"campo": "mensagem"}`, formato conveniente para o cliente consumir — com a ressalva de que, como `Map` não permite chaves duplicadas, se dois erros ocorrerem no mesmo campo, só a última mensagem sobrevive.

---

## 6. O que são DTOs, vantagens e quando (não) usar

### Contexto
```java
// payload/CategoryDTO.java
@Data @NoArgsConstructor @AllArgsConstructor
public class CategoryDTO {
    private Long categoryId;
    private String categoryName;
}

// payload/CategoryResponse.java — DTO "envelope"
@Data @NoArgsConstructor @AllArgsConstructor
public class CategoryResponse {
    private List<CategoryDTO> content;
}
```
Uso em `CategoryServiceImpl.getAllCategories()`:
```java
List<Category> categories = categoryRepository.findAll();               // entidades JPA
List<CategoryDTO> categoryDTOS = categories.stream()
        .map(category -> modelMapper.map(category, CategoryDTO.class))  // Entity -> DTO
        .toList();
CategoryResponse categoryResponse = new CategoryResponse();
categoryResponse.setContent(categoryDTOS);
```

### Explicação
DTO (Data Transfer Object) é um objeto sem lógica de negócio nem anotações de persistência, cuja única função é carregar dados entre camadas/sistemas. Diferente da entidade `Category` (que representa uma linha da tabela `categories`, com `@Entity`/`@Id`/`@GeneratedValue`), o `CategoryDTO` representa o formato que trafega pela API. `CategoryResponse` é um DTO "envelope" — representa a resposta como um todo, preparado para no futuro incluir metadados de paginação.

A conversão Entity → DTO é feita automaticamente pelo `ModelMapper` (reflection, casa campos por nome), evitando escrever `dto.setX(entity.getX())` manualmente para cada campo.

### Vantagens de separar DTO de Entity
1. **Evita vazamento de detalhes internos** — campos sensíveis/internos da entidade não aparecem automaticamente na API.
2. **Evita `LazyInitializationException`** — se a entidade tivesse relacionamentos `@OneToMany`/`@ManyToOne` LAZY, devolver ela direto arrisca o Jackson tentar serializar um relacionamento não carregado, fora da sessão do Hibernate.
3. **Desacopla o contrato da API do schema do banco** — mudar a entidade não quebra automaticamente quem consome a API.
4. **Previne "mass assignment"** (quando usado também na entrada) — cliente não consegue setar campos que não estão no DTO de entrada.
5. **Formatos diferentes por caso de uso** — um DTO de criação pode omitir o ID; um DTO de listagem pode ter campos calculados que não existem na entidade.

### Quando não vale a pena
Projetos muito pequenos/protótipos, CRUDs internos simples sem relacionamentos complexos, ou APIs internas não expostas externamente — o custo de manter DTO + mapeamento sincronizado com a entidade pode superar o benefício. Não é uma regra absoluta, é um trade-off entre segurança/flexibilidade (a favor do DTO) e simplicidade (a favor da entidade direta).

### `ModelMapper` vs alternativas
`ModelMapper` mapeia via reflection em runtime — simples de configurar, mas erros de mapeamento só aparecem em tempo de execução. `MapStruct` é uma alternativa mais moderna que gera código de mapeamento em tempo de compilação (mais rápido, erros pegos na compilação), com configuração um pouco mais elaborada.

### Inconsistência encontrada no projeto (✅ corrigida em seguida — ver seção 7)
A saída dos endpoints já usa DTO (`CategoryResponse` no GET), mas a entrada (POST/PUT) ainda recebia a entidade `Category` direto no `@RequestBody`. Resolvido pouco depois: o Controller passou a usar `CategoryDTO` também na entrada.

### 📌 Resumo
DTO (Data Transfer Object) é um objeto simples, sem lógica de negócio nem anotações de persistência, cuja única função é carregar dados entre camadas — no projeto, `CategoryDTO` e `CategoryResponse` representam o formato que trafega pela API, enquanto `Category` (a entidade JPA) representa a linha da tabela no banco; a conversão entre os dois é feita automaticamente pelo `ModelMapper`, que copia campos por nome via reflection. A vantagem de separar DTO de entidade é evitar vazar detalhes internos de persistência na API pública, prevenir erros de serialização com relacionamentos JPA lazy (`LazyInitializationException`), desacoplar o contrato da API de mudanças no schema do banco, e (quando usado também na entrada) prevenir "mass assignment" de campos sensíveis; a desvantagem é a camada extra de classes e mapeamento a manter, o que faz DTO valer menos a pena em protótipos pequenos ou APIs internas simples — é sempre um trade-off entre segurança/flexibilidade e simplicidade. No projeto atual, essa separação só foi aplicada na saída (GET); os endpoints de entrada (POST/PUT) ainda recebem a entidade `Category` direto, uma inconsistência conhecida e documentada como melhoria futura.

---

## 7. Paginação, ordenação, `@RequestParam`, `APIResponse` e simetria de DTO

### Contexto
Várias mudanças chegaram juntas: paginação/ordenação real no `GET`, um envelope `APIResponse` para erros, e o `CategoryDTO` passou a ser usado também na entrada dos endpoints (não só na saída).

```java
// AppConstants — valores padrão centralizados
public class AppConstants {
    public static final String PAGE_NUMBER = "0";
    public static final String PAGE_SIZE = "50";
    public static final String SORT_CATEGORIES_BY = "categoryId";
    public static final String SORT_DIR = "asc";
}

// Controller — @RequestParam lendo a query string
public ResponseEntity<CategoryResponse> getAllCategories(
        @RequestParam(name = "pageNumber", defaultValue = AppConstants.PAGE_NUMBER, required = false) Integer pageNumber,
        @RequestParam(name = "pageSize", defaultValue = AppConstants.PAGE_SIZE, required = false) Integer pageSize,
        @RequestParam(name = "sortBy", defaultValue = AppConstants.SORT_CATEGORIES_BY, required = false) String sortBy,
        @RequestParam(name = "sortOrder", defaultValue = AppConstants.SORT_DIR, required = false) String sortOrder) {
    CategoryResponse categoryResponse = categoryService.getAllCategories(pageNumber, pageSize, sortBy, sortOrder);
    return new ResponseEntity<>(categoryResponse, HttpStatus.OK);
}

// Service — Pageable + Sort do Spring Data
Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
        ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
Page<Category> categoryPage = categoryRepository.findAll(pageDetails);
// categoryPage.getContent(), getNumber(), getSize(), getTotalElements(), getTotalPages(), isLast()
```

```java
// APIResponse — envelope padronizado para erros
public class APIResponse {
    public String message;
    private boolean status;
}
// usado no MyGlobalExceptionHandler:
new ResponseEntity<>(new APIResponse(e.getMessage(), false), HttpStatus.NOT_FOUND);
```

```java
// Controller agora simétrico: DTO na entrada E na saída
public ResponseEntity<CategoryDTO> createCategory(@Valid @RequestBody CategoryDTO categoryDTO) {
    CategoryDTO savedCategoryDTO = categoryService.createCategory(categoryDTO);
    return new ResponseEntity<>(savedCategoryDTO, HttpStatus.CREATED);
}
// Service converte nos dois sentidos:
Category category = modelMapper.map(categoryDTO, Category.class);   // DTO -> Entity
...
return modelMapper.map(savedCategory, CategoryDTO.class);            // Entity -> DTO
```

### Explicação
- **`Pageable`/`PageRequest`** (interface/impl) representa "qual página, de que tamanho, com que ordenação"; `JpaRepository.findAll(Pageable)` já vem pronto e devolve `Page<Category>` (não `List`), que carrega os itens da página **e** metadados (total de elementos, total de páginas, se é a última) — sem escrever SQL de `LIMIT`/`OFFSET`/`ORDER BY` manualmente. Por trás, o Spring Data roda uma query paginada + um `SELECT COUNT(*)`.
- **`Sort.by(campo).ascending()/.descending()`** representa a ordenação de forma independente do banco; passado junto no `PageRequest.of(...)`, vira um único `ORDER BY` na query.
- **`@RequestParam`** lê parâmetros da **query string** (`?pageNumber=1&sortBy=categoryName`), diferente de `@PathVariable` (segmento da URL) e `@RequestBody` (corpo JSON). `defaultValue` (sempre String) permite que a chamada sem parâmetros continue funcionando.
- **`AppConstants`** centraliza os valores padrão em constantes `static final`, evitando strings mágicas espalhadas e facilitando trocar o padrão em um único lugar.
- **`CategoryResponse`** ganhou campos de paginação (`pageNumber`, `pageSize`, `totalElements`, `totalpages`, `lastPage`), preenchidos a partir do `Page<Category>` — permite ao cliente construir uma UI de paginação sem chamada extra.
- **`APIResponse`** é um DTO envelope `{message, status}` que padroniza as respostas de erro do handler global (antes eram strings cruas) — mais consistente com o resto da API, que já fala a língua de objetos estruturados.
- **Simetria de DTO**: a inconsistência da seção 6 foi corrigida — agora `createCategory`/`updateCategory`/`deleteCategory` recebem e devolvem `CategoryDTO`, nunca a entidade `Category` diretamente; o service faz a conversão DTO→Entity (antes de salvar) e Entity→DTO (antes de responder) via `ModelMapper`.

### 📌 Resumo
As mudanças recentes adicionaram paginação e ordenação reais ao endpoint de listagem: o Controller recebe `pageNumber`, `pageSize`, `sortBy` e `sortOrder` via `@RequestParam` (com padrões centralizados em `AppConstants`), monta um `Pageable`/`Sort` do Spring Data e chama `categoryRepository.findAll(pageDetails)`, que devolve um `Page<Category>` com os itens da página e metadados (total de elementos, total de páginas, última página), tudo isso agora exposto em `CategoryResponse`; surgiu também `APIResponse`, um DTO simples `{message, status}` que padroniza as respostas de erro do `MyGlobalExceptionHandler` (antes strings cruas); e, fechando um ponto pendente da seção 6, os endpoints de criação/atualização/remoção passaram a usar `CategoryDTO` tanto na entrada quanto na saída, com o service convertendo nos dois sentidos via `ModelMapper` — a entidade `Category` não atravessa mais a fronteira do Controller.

---

## 8. Revisão completa do módulo de Categorias — bugs encontrados e corrigidos

### Contexto
Pedido de revisão de todo o módulo (Controller, Service, Repository, Model, DTOs, Exceptions, Config) em busca de correções/melhorias, feita após a introdução de paginação/DTOs simétricos (seção 7).

### Bugs corrigidos

**1. Validação de entrada quebrada silenciosamente**
Quando o Controller passou a receber `CategoryDTO` em vez de `Category`, a validação `@NotBlank`/`@Size(min=5)` continuou só na entidade `Category` — que não é mais validada diretamente pelo `@Valid` do Controller. `CategoryDTO` não tinha nenhuma anotação de validação, então nomes vazios/curtos passavam sem erro 400.
```java
// CategoryDTO.java — antes: sem nenhuma anotação de validação
private String categoryName;

// Depois:
@NotBlank
@Size(min = 5, message = "Category name must contain at least 5 characters")
private String categoryName;
```

**2. `categoryId` vazando no POST de criação (reincidência do bug de `StaleObjectStateException`)**
`modelMapper.map(categoryDTO, Category.class)` copiava também o `categoryId`, se o cliente mandasse um no corpo do POST. Como `categoryId` usa `@GeneratedValue(IDENTITY)`, isso fazia o Spring Data tentar um `UPDATE` (`merge`) em vez de `INSERT` (`persist`) — o mesmo bug da seção 3, só que reintroduzido por um caminho diferente.
```java
Category category = modelMapper.map(categoryDTO, Category.class);
category.setCategoryId(null);   // garante que toda criação sempre gera um ID novo
```

**3. Race condition na checagem de nome duplicado**
`findByCategoryName` (checa) e `save()` (grava) eram operações separadas sem nenhuma trava — duas requisições concorrentes com o mesmo nome podiam ambas passar pela checagem antes de qualquer uma salvar. Corrigido em duas camadas: constraint `UNIQUE` real no banco (rede de segurança contra concorrência) + captura da exceção que o Hibernate lança quando ela é violada.
```java
// Category.java
@Column(unique = true)
private String categoryName;

// CategoryServiceImpl.createCategory
try {
    savedCategory = categoryRepository.save(category);
} catch (DataIntegrityViolationException e) {
    throw new APIException("Category with the name " + category.getCategoryName() + " already exists !!!");
}
```

**4. `application.properties` com propriedade incorreta**
```properties
# Antes (misturava duas propriedades diferentes num valor sem sentido):
spring.jpa.properties.hibernate.format_sql=create-drop

# Depois:
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.hibernate.ddl-auto=create-drop
```
O app "funcionava" antes porque o Spring Boot já assume `create-drop` por padrão para bancos embarcados como o H2 — mas a intenção não estava explícita, e o SQL não saía formatado no log por causa do valor inválido em `format_sql`.

### Melhorias identificadas, ainda não aplicadas (documentadas em `DOCUMENTACAO-PROJETO.md`)
- Sem handler genérico (`@ExceptionHandler(Exception.class)`) para exceções inesperadas (ex.: `pageNumber` negativo).
- `sortBy` não validado contra os campos reais da entidade.
- `Category.categoryName` sem `@Size(max=...)`.
- `CategoryResponse.totalpages` foge do padrão camelCase (deveria ser `totalPages`).
- `APIResponse.message` é campo `public`, inconsistente com `status` (`private` + getter/setter).

### 📌 Resumo
A revisão completa do módulo de Categorias encontrou e corrigiu quatro problemas: a validação de entrada (`@NotBlank`/`@Size`) tinha ficado "órfã" na entidade `Category` depois que o Controller passou a validar `CategoryDTO` (que não tinha as anotações) — corrigido movendo as anotações para o DTO; o `categoryId` podia vazar do corpo de um POST de criação e reintroduzir o bug de `StaleObjectStateException` já visto antes — corrigido zerando o ID explicitamente antes de salvar; a checagem de nome duplicado tinha uma race condition (checagem e gravação são operações separadas, sem trava) — corrigido com uma constraint `UNIQUE` real no banco mais a captura da `DataIntegrityViolationException` correspondente; e uma propriedade de configuração (`format_sql`) estava recebendo um valor de outra propriedade (`ddl-auto`) por engano — corrigido separando as duas. Outras melhorias menores (handler genérico de exceções, validação de `sortBy`, nomenclatura de `totalpages`) ficaram documentadas como pendências, sem necessidade de correção imediata.

---

*Arquivo criado para consulta pessoal de estudo — atualizar conforme novos conceitos forem estudados no projeto.*
